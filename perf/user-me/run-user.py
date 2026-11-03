"""내 정보 조회 k6 실행과 측정 자료 보관. 부하는 k6가 발생시킨다."""
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_id")
    parser.add_argument("--vus", type=int, default=100)
    parser.add_argument(
        "--duration", choices=["30s", "60s", "90s"], default="60s"
    )
    args = parser.parse_args()

    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.run_id):
        raise ValueError("실행 ID는 영문·숫자·밑줄·하이픈만 사용하세요.")
    if not 1 <= args.vus <= 100:
        raise ValueError("현재 준비된 데이터에서는 VU 1~100만 지원합니다.")

    folder = ROOT / "results" / args.run_id
    folder.mkdir(parents=True, exist_ok=False)

    # 검증 기준과 로그인 토큰을 갱신한다.
    subprocess.run(
        [sys.executable, str(ROOT / "prepare-user.py")], check=True
    )
    subprocess.run(
        [sys.executable, str(LIST_ROOT / "prepare.py")], check=True
    )

    pid = (LIST_ROOT / ".local/app.pid").read_text().strip()
    if not pid.isdigit():
        raise RuntimeError("앱 PID 파일이 올바르지 않습니다.")
    output(["ps", "-p", pid, "-o", "pid="])

    metadata = {
        "runId": args.run_id,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "toolCommit": output(["git", "rev-parse", "HEAD"]),
        "workingChanges": output(["git", "status", "--short"]),
        "appPid": int(pid),
        "appCodeReference": "49b39a1",
        "appCodeReferenceNote": (
            "기존 실행 앱에 대해 수동 기록한 기준. "
            "실행 바이너리에서 자동 검증한 값은 아님."
        ),
        "endpoint": "GET /api/users/me",
        "mode": "load",
        "vus": args.vus,
        "duration": args.duration,
        "thinkSeconds": 1,
        "model": "constant-vus closed model",
        "requestMix": "각 VU가 본인 회원 정보를 반복 조회",
        "fixture": "합성 테스트 회원 100명",
        "preflight": "미인증 내 정보 조회 401",
        "k6": output(["docker", "run", "--rm", IMAGE, "version"]),
        "limitations": [
            "앱·DB·부하 발생기가 같은 Mac의 자원을 공유함",
            "합성 테스트 회원 100명을 사용함",
            "최대 처리 능력이나 배포 서버 성능으로 일반화할 수 없음",
            "SQL 통계는 누적값이므로 전후 차이로 해석해야 함",
        ],
    }

    def save_metadata():
        (folder / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    save_metadata()

    # 합성 회원의 검증 기준만 복사하며 토큰은 복사하지 않는다.
    (folder / "fixture.json").write_text(
        (ROOT / ".local/user-fixture.json").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (folder / "user-me.js").write_text(
        (ROOT / "user-me.js").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (folder / "db-before.txt").write_text(sql("""
        SELECT VERSION();
        SELECT COUNT(*) AS user_count FROM users;
        SELECT COUNT(*) AS test_user_count
        FROM users
        WHERE email REGEXP '^perf[0-9]{4}@example[.]test$';
        SHOW INDEX FROM users;
    """))
    (folder / "sql-digests-before.txt").write_text(
        sql(DIGEST_QUERY, admin=True)
    )

    stop = threading.Event()

    def sample():
        with (folder / "resource-samples.jsonl").open("w") as log:
            while not stop.is_set():
                snapshot = {
                    "at": datetime.now(timezone.utc).isoformat()
                }
                try:
                    snapshot["containers"] = output([
                        "docker", "stats", "--no-stream",
                        "--format", "{{json .}}",
                        "attraction-order-perf-mysql-1",
                        "attraction-order-perf-redis-1",
                    ])
                    snapshot["appProcess"] = output([
                        "ps", "-p", pid, "-o", "pid=,%cpu=,rss=",
                    ])
                except subprocess.CalledProcessError:
                    snapshot["samplingError"] = True

                log.write(json.dumps(snapshot) + "\n")
                log.flush()
                stop.wait(2)

    command = [
        "docker", "run", "--rm",
        "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{ROOT.parent}:/scripts:ro",
        "-v", f"{folder}:/results",
        "-e", "MODE=load",
        "-e", f"VUS={args.vus}",
        "-e", f"DURATION={args.duration}",
        "-e", "K6_WEB_DASHBOARD=true",
        "-e", "K6_WEB_DASHBOARD_EXPORT=/results/dashboard.html",
        IMAGE, "run",
        "--out", "json=/results/metrics.json",
        "/scripts/user-me/user-me.js",
    ]

    worker = threading.Thread(target=sample, daemon=True)
    worker.start()
    code = None

    try:
        with (folder / "terminal.txt").open("w") as log:
            child = subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            for line in child.stdout:
                print(line, end="")
                log.write(line)
            code = child.wait()
    finally:
        stop.set()
        worker.join(timeout=10)
        metadata["finishedAt"] = datetime.now(timezone.utc).isoformat()
        metadata["exitCode"] = code
        save_metadata()

    (folder / "sql-digests-after.txt").write_text(
        sql(DIGEST_QUERY, admin=True)
    )
    print("결과 폴더:", folder)
    raise SystemExit(code)


if __name__ == "__main__":
    main()