"""주문 생성·취소 부하 실행과 실행 후 DB 검증."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parent
LIST_ROOT = ROOT.parent / "order-list"
REPO = ROOT.parent.parent

sys.path.insert(0, str(LIST_ROOT))
from run import IMAGE, output, sql

DIGEST_QUERY = """
SELECT
    DIGEST,
    DIGEST_TEXT,
    COUNT_STAR,
    SUM_ROWS_EXAMINED,
    SUM_ROWS_AFFECTED,
    SUM_TIMER_WAIT
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'attraction_perf'
  AND (
      DIGEST_TEXT LIKE 'SELECT%'
      OR DIGEST_TEXT LIKE 'INSERT%'
      OR DIGEST_TEXT LIKE 'UPDATE%'
  )
ORDER BY SUM_TIMER_WAIT DESC;
"""


def now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_id")
    parser.add_argument("--app-code", required=True)
    parser.add_argument("--vus", type=int, default=10)
    parser.add_argument(
        "--phase", choices=["warmup", "baseline", "after"],
        default="baseline",
    )
    args = parser.parse_args()

    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,60}", args.run_id):
        raise ValueError("실행 ID 형식이 올바르지 않습니다.")
    if not re.fullmatch(r"[0-9a-fA-F]{7,40}", args.app_code):
        raise ValueError("앱 코드 기준은 커밋 해시로 입력하세요.")
    if not 1 <= args.vus <= 10:
        raise ValueError("이번 실행기는 VU 1~10만 지원합니다.")

    folder = ROOT / "results" / args.run_id
    folder.mkdir(parents=True, exist_ok=False)

    pid = (LIST_ROOT / ".local/app.pid").read_text().strip()
    if not pid.isdigit():
        raise RuntimeError("앱 PID가 올바르지 않습니다.")

    launch_command = output(["ps", "-p", pid, "-o", "command="])
    if "attraction-reservation-0.0.1-SNAPSHOT.jar" not in launch_command:
        raise RuntimeError("예상한 테스트 앱 프로세스가 아닙니다.")

    subprocess.run(
        [sys.executable, str(ROOT / "prepare-load.py")],
        check=True,
    )

    fixture = json.loads(
        (ROOT / ".local/load-fixture.json").read_text(encoding="utf-8")
    )

    jar = REPO / "build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar"
    digest = hashlib.sha256()
    with jar.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)

    container = "attraction-order-write-k6-" + args.run_id
    metadata = {
        "runId": args.run_id,
        "phase": args.phase,
        "startedAt": now(),
        "toolCommit": output(["git", "rev-parse", "HEAD"]),
        "workingChanges": output(["git", "status", "--short"]),
        "appPid": int(pid),
        "appLaunchCommand": launch_command,
        "appCodeReference": args.app_code,
        "appCodeReferenceNote": "실행자가 지정한 기록. 실행 바이너리의 커밋 자동 검증 아님.",
        "jarOnDiskSha256": digest.hexdigest(),
        "jarHashNote": "실행 시점 디스크의 JAR 해시. 실행 중인 메모리와 자동 대조한 값은 아님.",
        "vus": args.vus,
        "accountCount": args.vus,
        "mode": "load",
        "duration": "60s",
        "gracefulStop": "30s",
        "thinkSeconds": 1,
        "scenario": "같은 상품을 주문하고 본인 주문을 취소한 뒤 반복",
        "productId": fixture["productId"],
        "initialStock": fixture["initialStock"],
        "visitDate": fixture["visitDate"],
        "k6": output(["docker", "run", "--rm", IMAGE, "version"]),
        "dockerResources": output([
            "docker", "info", "--format",
            "CPU={{.NCPU}} memoryBytes={{.MemTotal}}",
        ]),
        "limitations": [
            "앱·DB·k6가 같은 Mac의 자원을 공유함",
            "모든 VU가 같은 상품을 사용하므로 상품 행의 잠금 경합 포함",
            "생성한 주문을 모두 취소하는 비교용 시나리오",
            "취소된 이력은 삭제하지 않아 실행마다 데이터가 증가함",
        ],
    }

    def save_metadata():
        (folder / "metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    save_metadata()

    for source, target in [
        (ROOT / ".local/load-fixture.json", "fixture.json"),
        (ROOT / "order-write.js", "order-write.js"),
        (ROOT / "prepare-load.py", "prepare-load.py"),
        (ROOT / "verify-load.py", "verify-load.py"),
        (ROOT / "run-write.py", "run-write.py"),
    ]:
        (folder / target).write_text(
            source.read_text(encoding="utf-8"), encoding="utf-8"
        )

    (folder / "db-before.txt").write_text(sql("""
        SELECT VERSION();
        SHOW CREATE TABLE ticket_products;
        SHOW CREATE TABLE orders;
        SHOW CREATE TABLE order_items;
        SHOW CREATE TABLE payments;
    """), encoding="utf-8")

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
        "/scripts/order-write/order-write.js",
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
        metadata["k6FinishedAt"] = now()
        metadata["k6ExitCode"] = code
        save_metadata()

    (folder / "sql-digests-after.txt").write_text(
        sql(DIGEST_QUERY, admin=True), encoding="utf-8"
    )

    verification = subprocess.run(
        [sys.executable, str(ROOT / "verify-load.py"), args.run_id],
        capture_output=True, text=True,
    )
    verification_output = verification.stdout + verification.stderr
    print(verification_output, end="")
    (folder / "verification-terminal.txt").write_text(
        verification_output, encoding="utf-8"
    )

    final_code = code if code else verification.returncode
    metadata["verificationExitCode"] = verification.returncode
    metadata["exitCode"] = final_code
    metadata["finishedAt"] = now()
    save_metadata()

    print("결과 폴더:", folder)
    raise SystemExit(final_code)


if __name__ == "__main__":
    main()