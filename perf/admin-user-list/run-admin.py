"""관리자 목록 k6 실행 및 측정 자료 보관."""
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
    parser.add_argument("--vus", type=int, default=10)
    parser.add_argument(
        "--phase", choices=["warmup", "baseline", "after"],
        default="baseline",
    )
    args = parser.parse_args()

    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,60}", args.run_id):
        raise ValueError("실행 ID는 영문·숫자·밑줄·하이픈 1~60자입니다.")
    if not 1 <= args.vus <= 10:
        raise ValueError("이번 실험은 VU 1~10만 지원합니다.")

    folder = ROOT / "results" / args.run_id
    folder.mkdir(parents=True, exist_ok=False)

    subprocess.run(
        [sys.executable, str(ROOT / "prepare-admin.py")],
        check=True,
    )

    pid = (LIST_ROOT / ".local/app.pid").read_text().strip()
    if not pid.isdigit():
        raise RuntimeError("앱 PID가 올바르지 않습니다.")
    output(["ps", "-p", pid, "-o", "pid="])

    fixture = json.loads(
        (ROOT / ".local/fixture.json").read_text(encoding="utf-8")
    )
    container = "attraction-admin-list-k6-" + args.run_id

    metadata = {
        "runId": args.run_id,
        "phase": args.phase,
        "startedAt": now(),
        "endpoint": "GET /api/admin/users",
        "toolCommit": output(["git", "rev-parse", "HEAD"]),
        "workingChanges": output(["git", "status", "--short"]),
        "appPid": int(pid),
        "appCodeReference": "49b39a1",
        "appCodeReferenceNote": "기존 실행 앱의 수동 기록. 바이너리 자동 검증 아님.",
        "vus": args.vus,
        "accountCount": 1,
        "mode": "load",
        "duration": "60s",
        "thinkSeconds": 1,
        "model": "constant-vus closed model",
        "pageSize": fixture["size"],
        "pages": sorted(map(int, fixture["pages"])),
        "totalElements": fixture["totalElements"],
        "k6": output(["docker", "run", "--rm", IMAGE, "version"]),
        "dockerResources": output([
            "docker", "info", "--format",
            "CPU={{.NCPU}} memoryBytes={{.MemTotal}}",
        ]),
        "limitations": [
            "앱·DB·k6가 같은 Mac의 자원을 공유함",
            "모든 VU가 동일한 관리자 계정을 사용함",
            "실제 관리자 수나 최대 처리 능력을 입증하는 실험이 아님",
            "현재 실행기는 JVM·연결 풀 설정을 자동 검증하지 않음",
        ],
    }

    def save_metadata():
        (folder / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    save_metadata()

    for source, target in [
        (ROOT / ".local/fixture.json", "fixture.json"),
        (ROOT / "admin-user-list.js", "admin-user-list.js"),
        (ROOT / "prepare-admin.py", "prepare-admin.py"),
        (ROOT / "run-admin.py", "run-admin.py"),
    ]:
        (folder / target).write_text(
            source.read_text(encoding="utf-8"), encoding="utf-8"
        )

    (folder / "db-before.txt").write_text(sql("""
        SELECT VERSION();
        SELECT role, status, COUNT(*)
        FROM users GROUP BY role, status;
        SHOW INDEX FROM users;
    """), encoding="utf-8")

    plans = []
    for page in sorted(map(int, fixture["pages"])):
        offset = page * fixture["size"]
        plans.append(f"""
            SELECT 'page={page}';
            EXPLAIN ANALYZE SELECT * FROM users
            WHERE status <> 'WITHDRAWN'
            ORDER BY created_at DESC, id DESC
            LIMIT {fixture["size"]} OFFSET {offset};
        """)
    plans.append("""
        EXPLAIN ANALYZE SELECT COUNT(*) FROM users
        WHERE status <> 'WITHDRAWN';
    """)

    (folder / "plans-before.txt").write_text(
        sql("\n".join(plans)), encoding="utf-8"
    )
    (folder / "sql-digests-before.txt").write_text(
        sql(DIGEST_QUERY, admin=True), encoding="utf-8"
    )

    stop = threading.Event()

    def sample():
        with (folder / "resource-samples.jsonl").open(
            "w", encoding="utf-8"
        ) as log:
            while not stop.wait(2):
                snapshot = {"at": now()}
                commands = {
                    "appProcess": [
                        "ps", "-p", pid, "-o", "pid=,%cpu=,rss=",
                    ],
                    "containers": [
                        "docker", "stats", "--no-stream",
                        "--format", "{{json .}}",
                        "attraction-order-perf-mysql-1",
                        "attraction-order-perf-redis-1",
                        container,
                    ],
                }
                for name, command in commands.items():
                    try:
                        result = subprocess.run(
                            command, capture_output=True, text=True,
                            check=True, timeout=15,
                        )
                        snapshot[name] = result.stdout.strip()
                    except (
                        subprocess.CalledProcessError,
                        subprocess.TimeoutExpired,
                    ):
                        snapshot[name + "Unavailable"] = True

                log.write(json.dumps(snapshot) + "\n")
                log.flush()

    command = [
        "docker", "run", "--rm", "--name", container,
        "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{ROOT.parent}:/scripts:ro",
        "-v", f"{folder}:/results",
        "-e", "MODE=load",
        "-e", f"VUS={args.vus}",
        "-e", "K6_WEB_DASHBOARD=true",
        "-e", "K6_WEB_DASHBOARD_EXPORT=/results/dashboard.html",
        IMAGE, "run", "--out", "json=/results/metrics.json",
        "/scripts/admin-user-list/admin-user-list.js",
    ]

    worker = threading.Thread(target=sample, daemon=True)
    code = None

    try:
        with (folder / "terminal.txt").open(
            "w", encoding="utf-8"
        ) as log:
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
            capture_output=True, timeout=15, check=False,
        )
        code = 130
    finally:
        stop.set()
        if worker.ident is not None:
            worker.join(timeout=20)
        metadata["finishedAt"] = now()
        metadata["exitCode"] = code
        save_metadata()

    (folder / "sql-digests-after.txt").write_text(
        sql(DIGEST_QUERY, admin=True), encoding="utf-8"
    )
    print("결과 폴더:", folder)
    raise SystemExit(code)


if __name__ == "__main__":
    main()