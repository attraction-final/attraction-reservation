"""개인 인증 실험 실행 및 결과·자원 표본 보관."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import threading
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
COMPOSE = ROOT.parent / "order-list" / "compose.yaml"
K6_IMAGE = (
    "grafana/k6@sha256:"
    "3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e"
)


def captured(command):
    return subprocess.run(
        command,
        cwd=REPO,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=15,
    )


def sample_resources(folder, app_pid, container_name, stop):
    path = folder / "resources.jsonl"

    with path.open("x", encoding="utf-8") as output:
        while not stop.is_set():
            sample = {
                "sampledAt": datetime.now(timezone.utc).isoformat(),
            }

            commands = {
                "appPs": [
                    "ps", "-p", app_pid,
                    "-o", "pid=,%cpu=,rss=",
                ],
                "dockerStats": [
                    "docker", "stats", "--no-stream",
                    "--format", "{{json .}}",
                    "attraction-order-perf-mysql-1",
                    "attraction-order-perf-redis-1",
                    container_name,
                ],
            }

            for label, command in commands.items():
                try:
                    result = captured(command)
                    sample[label] = {
                        "exitCode": result.returncode,
                        "stdout": result.stdout.strip(),
                        "stderr": result.stderr.strip(),
                    }
                except (OSError, subprocess.TimeoutExpired) as error:
                    sample[label] = {
                        "error": type(error).__name__,
                    }

            output.write(
                json.dumps(sample, ensure_ascii=False) + "\n"
            )
            output.flush()
            stop.wait(5)


def main():
    parser = argparse.ArgumentParser(
        description="재발급 60초 측정과 자원 표본을 보관합니다."
    )
    parser.add_argument("run_name")
    parser.add_argument("--vus", type=int, required=True)
    args = parser.parse_args()

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", args.run_name):
        parser.error("실행 이름은 영문 소문자·숫자·하이픈만 사용하세요.")

    users_path = ROOT / ".local/users.json"
    users = json.loads(users_path.read_text(encoding="utf-8"))

    if not 1 <= args.vus <= len(users):
        parser.error(f"--vus는 1~{len(users)} 사이여야 합니다.")

    app_pid = (ROOT / ".local/app.pid").read_text().strip()
    if not app_pid.isdigit():
        raise RuntimeError("앱 PID 파일이 올바르지 않습니다.")

    listener = captured([
        "lsof", "-nP", "-t", "-a", "-p", app_pid,
        "-iTCP:18094", "-sTCP:LISTEN",
    ])
    if app_pid not in listener.stdout.split():
        raise RuntimeError("기록된 앱이 18094 포트에서 실행 중이 아닙니다.")

    jar = REPO / "build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar"
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()

    source = captured(["git", "rev-parse", "HEAD"])
    if source.returncode != 0:
        raise RuntimeError("소스 커밋 확인 실패")

    folder = ROOT / "results" / args.run_name

    # 기존 실행 결과를 덮어쓰지 않는다.
    folder.mkdir(parents=True, exist_ok=False)

    # 로그인과 Redis 기준 준비는 부하 측정 전에 끝낸다.
    subprocess.run(
        [
            sys.executable, str(ROOT / "prepare-load.py"),
            "--users", str(args.vus),
        ],
        cwd=REPO,
        stdin=subprocess.DEVNULL,
        check=True,
    )
    baseline_path = ROOT / ".local/load-accounts.json"
    baseline_hash = hashlib.sha256(
        baseline_path.read_bytes()
    ).hexdigest()

    for filename in [
        "auth-refresh.js",
        "application-perf.yaml",
        "prepare-users.py",
        "run-refresh.py",
        "prepare-load.py",
        "check-refresh.py",
        "verify-load.py",
    ]:
        shutil.copy2(ROOT / filename, folder / filename)

    (folder / "source-commit.txt").write_text(
        source.stdout, encoding="utf-8"
    )
    (folder / "app-jar-sha256.txt").write_text(
        f"{jar_hash}  {jar.name}\n", encoding="utf-8"
    )

    patch = captured([
        "git", "diff", "HEAD", "--",
        "src/main/java", "src/test/java",
    ])
    if patch.returncode != 0:
        raise RuntimeError("소스 변경 기록 실패")
    (folder / "source-change.patch").write_text(
        patch.stdout, encoding="utf-8"
    )

    container_name = f"perf-auth-{args.run_name}"
    command = [
        "docker", "run", "--rm",
        "--name", container_name,
        "--user", f"{__import__('os').getuid()}:{__import__('os').getgid()}",
        "-v", f"{REPO / 'perf'}:/scripts:ro",
        "-v", f"{folder}:/results",
        "-e", "MODE=load",
        "-e", f"VUS={args.vus}",
        K6_IMAGE,
        "run", "--out", "json=/results/metrics.json",
        "/scripts/auth-refresh-capacity/auth-refresh.js",
    ]

    metadata = {
        "vus": args.vus,
        "mode": "load",
        "redisDatabase": 3,
        "sessionBaselineSha256": baseline_hash,
        "duration": "60s",
        "sleepSeconds": 1,
        "fixtureCount": len(users),
        "fixtureSha256": hashlib.sha256(
            users_path.read_bytes()
        ).hexdigest(),
        "appPid": int(app_pid),
        "k6Image": K6_IMAGE,
        "startedAt": datetime.now(timezone.utc).isoformat(),
    }
    (folder / "run.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(f"재발급 측정 시작: {args.vus} VU / 60초", flush=True)
    print(f"결과 폴더: {folder}", flush=True)

    stop = threading.Event()
    monitor = threading.Thread(
        target=sample_resources,
        args=(folder, app_pid, container_name, stop),
        daemon=True,
    )

    try:
        with (folder / "console.log").open(
            "x", encoding="utf-8"
        ) as log:
            process = subprocess.Popen(
                command,
                cwd=REPO,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            monitor.start()
            exit_code = process.wait()
    except KeyboardInterrupt:
        captured(["docker", "stop", "--time", "5", container_name])
        raise
    finally:
        stop.set()
        if monitor.ident is not None:
            monitor.join()

    (folder / "exit-code.txt").write_text(
        f"{exit_code}\n", encoding="utf-8"
    )

    # 측정 중 준비 파일이 바뀌면 잘못된 Redis 기준으로 비교하지 않는다.
    if hashlib.sha256(baseline_path.read_bytes()).hexdigest() != baseline_hash:
        raise RuntimeError("측정 중 세션 기준이 변경됐습니다.")

    verification = subprocess.run(
        [
            sys.executable, str(ROOT / "verify-load.py"),
            args.run_name,
            "--vus", str(args.vus),
            "--mode", "load",
        ],
        cwd=REPO,
        stdin=subprocess.DEVNULL,
    )
    if verification.returncode != 0:
        raise RuntimeError(
            "응답·Redis 검증 실패. 결과 폴더의 기록을 확인하세요."
        )

    if exit_code != 0:
        raise RuntimeError(
            f"k6 종료 코드 {exit_code}. 결과 폴더의 console.log를 확인하세요."
        )

    summary = json.loads(
        (folder / "summary.json").read_text(encoding="utf-8")
    )
    metrics = summary["metrics"]

    attempts = metrics["auth_refresh_attempts"]["values"]["count"]
    successes = metrics["auth_refresh_successes"]["values"]["count"]
    latency = metrics["auth_refresh_duration"]["values"]
    failed = metrics["auth_refresh_failed"]["values"]["rate"]
    checks = metrics["checks"]["values"]

    if (
        attempts <= 0
        or attempts != successes
        or failed != 0
        or checks["rate"] != 1
        or metrics["http_req_failed"]["values"]["rate"] != 0
    ):
        raise RuntimeError("측정 결과에 실패 또는 건수 불일치가 있습니다.")

    print(f"재발급 시도·성공: 각각 {int(attempts)}건")
    print(
        f"평균 {latency['avg']:.2f}ms / "
        f"P95 {latency['p(95)']:.2f}ms / "
        f"P99 {latency['p(99)']:.2f}ms"
    )
    print(f"응답 검증: {int(checks['passes'])}개 통과")
    print("HTTP 및 재발급 실패율: 0")
    elapsed = metrics["auth_refresh_elapsed"]["values"]
    print(
        f"전체 호출: 평균 {elapsed['avg']:.2f}ms / "
        f"P95 {elapsed['p(95)']:.2f}ms / "
        f"P99 {elapsed['p(99)']:.2f}ms"
    )
    print("위의 첫 번째 시간 지표는 HTTP 응답 시간입니다.")
    print("자원 표본: resources.jsonl")
    print("완료·중단 반복 수는 console.log 마지막 줄도 확인하세요.")


if __name__ == "__main__":
    main()