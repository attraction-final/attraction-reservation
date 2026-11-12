"""단계별 내 정보 조회 부하 실행과 진단 자료 보관."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parent
LIST_ROOT = ROOT.parent / "order-list"

sys.path.insert(0, str(LIST_ROOT))
from run import IMAGE, DIGEST_QUERY, output, sql


def now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_id")
    parser.add_argument(
        "--vus", type=int, choices=[100, 300, 500, 1000, 2000],
        default=300,
    )
    args = parser.parse_args()

    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,60}", args.run_id):
        raise ValueError("실행 ID는 영문·숫자·밑줄·하이픈 1~60자입니다.")

    folder = ROOT / "results" / args.run_id
    folder.mkdir(parents=True, exist_ok=False)

    subprocess.run([
        sys.executable, str(ROOT / "prepare-load.py"),
        "--users", str(args.vus),
    ], check=True)

    pid = (LIST_ROOT / ".local/app.pid").read_text().strip()
    if not pid.isdigit():
        raise RuntimeError("앱 PID가 올바르지 않습니다.")
    output(["ps", "-p", pid, "-o", "pid="])

    container = "attraction-user-me-k6-" + args.run_id
    metadata = {
        "runId": args.run_id,
        "startedAt": now(),
        "endpoint": "GET /api/users/me",
        "phase": "단계별 부하 진단",
        "toolCommit": output(["git", "rev-parse", "HEAD"]),
        "workingChanges": output(["git", "status", "--short"]),
        "appPid": int(pid),
        "appCodeReference": "49b39a1",
        "appCodeReferenceNote": "기존 실행 앱의 수동 기록. 자동 검증 아님.",
        "accountSet": "load",
        "mode": "load",
        "vus": args.vus,
        "duration": "60s",
        "thinkSeconds": 1,
        "model": "constant-vus closed model",
        "testUserPoolSize": 2000,
        "k6Container": container,
        "k6Version": output(["docker", "run", "--rm", IMAGE, "version"]),
        "dockerResources": output([
            "docker", "info", "--format",
            "CPU={{.NCPU}} memoryBytes={{.MemTotal}}",
        ]),
        "abortCriteria": {
            "evaluationDelay": "30s",
            "cumulativeP95Ms": 2000,
            "cumulativeHttpFailureRate": 0.05,
            "note": "로컬 탐색용 중단 기준이며 운영 SLO가 아님",
        },
        "limitations": [
            "앱·DB·k6가 같은 Mac의 자원을 공유함",
            "합성 회원 2,000명 중 VU 수만큼 선택해 조회함",
            "SQL 통계는 누적 스냅샷의 차이로 해석함",
            "단계별 진단이며 운영 환경의 성능 보장이 아님",
        ],
    }
    metadata["abortCriteria"]["note"] = (
        "로컬 탐색용 중단 기준이며 운영 SLO가 아님"
    )

    def save_metadata():
        (folder / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    save_metadata()

    for source, target in [
        (ROOT / ".local/load-user-fixture.json", "fixture.json"),
        (ROOT / "user-me.js", "user-me.js"),
        (ROOT / "prepare-load.py", "prepare-load.py"),
    ]:
        (folder / target).write_text(
            source.read_text(encoding="utf-8"), encoding="utf-8"
        )

    (folder / "db-before.txt").write_text(sql("""
        SELECT VERSION();
        SELECT COUNT(*) AS user_count FROM users;
        SELECT COUNT(*) AS order_count FROM orders;
        SHOW INDEX FROM users;
    """))
    (folder / "sql-digests-before.txt").write_text(
        sql(DIGEST_QUERY, admin=True)
    )

    stop = threading.Event()

    def sample():
        with (folder / "resource-samples.jsonl").open("w") as log:
            while not stop.wait(2):
                snapshot = {"at": now()}
                try:
                    snapshot["appProcess"] = output([
                        "ps", "-p", pid, "-o", "pid=,%cpu=,rss=",
                    ])
                    snapshot["databaseContainers"] = output([
                        "docker", "stats", "--no-stream",
                        "--format", "{{json .}}",
                        "attraction-order-perf-mysql-1",
                        "attraction-order-perf-redis-1",
                    ])
                except subprocess.CalledProcessError:
                    snapshot["serverSamplingError"] = True

                try:
                    snapshot["loadGenerator"] = output([
                        "docker", "stats", "--no-stream",
                        "--format", "{{json .}}", container,
                    ])
                except subprocess.CalledProcessError:
                    # 실행 시작·종료 경계에서는 컨테이너가 없을 수 있다.
                    snapshot["loadGeneratorUnavailable"] = True

                log.write(json.dumps(snapshot) + "\n")
                log.flush()

    command = [
        "docker", "run", "--rm", "--name", container,
        "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{ROOT.parent}:/scripts:ro",
        "-v", f"{folder}:/results",
        "-e", "ACCOUNT_SET=load",
        "-e", "MODE=load",
        "-e", f"VUS={args.vus}",
        "-e", "DURATION=60s",
        "-e", "K6_WEB_DASHBOARD=true",
        "-e", "K6_WEB_DASHBOARD_EXPORT=/results/dashboard.html",
        IMAGE, "run", "--out", "json=/results/metrics.json",
        "/scripts/user-me/user-me.js",
    ]

    worker = threading.Thread(target=sample, daemon=True)
    code = None

    try:
        with (folder / "terminal.txt").open("w") as log:
            child = subprocess.Popen(
                command, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True,
            )
            worker.start()
            for line in child.stdout:
                print(line, end="")
                log.write(line)
            code = child.wait()
    except KeyboardInterrupt:
        subprocess.run(
            ["docker", "stop", "--time", "5", container],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=15,
            check=False,
        )
        code = 130
    finally:
        stop.set()
        if worker.ident is not None:
            worker.join(timeout=10)
        metadata["finishedAt"] = now()
        metadata["exitCode"] = code
        save_metadata()

    (folder / "sql-digests-after.txt").write_text(
        sql(DIGEST_QUERY, admin=True)
    )
    print("결과 폴더:", folder)
    raise SystemExit(code)


if __name__ == "__main__":
    main()