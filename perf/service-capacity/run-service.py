"""통합 부하 실행과 증빙 보관 및 DB 검증을 연결한다."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
IMAGE = (
    "grafana/k6@sha256:"
    "3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e"
)


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def ensure(condition, message):
    if not condition:
        raise RuntimeError(message)


def save(path, value):
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def captured(command):
    return subprocess.run(
        command,
        cwd=REPO,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        timeout=30,
        check=True,
    ).stdout.strip()


def run_tool(name, *arguments):
    subprocess.run(
        [sys.executable, str(ROOT / name), *arguments],
        cwd=REPO,
        stdin=subprocess.DEVNULL,
        check=True,
    )


def main():
    parser = argparse.ArgumentParser(
        description="통합 부하 준비·실행·검증을 연결합니다."
    )
    parser.add_argument("run_name")
    parser.add_argument("--vus", type=int, required=True)
    parser.add_argument("--mode", choices=["smoke", "load"], required=True)
    parser.add_argument(
        "--duration", choices=["120s", "300s"], default="120s"
    )
    parser.add_argument(
        "--jfr", action="store_true",
        help="Java 진단 기록을 추가합니다.",
    )
    args = parser.parse_args()
    jcmd = Path(os.environ.get("JAVA_HOME", "")) / "bin/jcmd"
    if args.jfr and not jcmd.is_file():
        raise RuntimeError("JAVA_HOME에 Java 17을 설정하세요.")


    ensure(
        re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", args.run_name),
        "결과 이름이 올바르지 않습니다.",
    )
    ensure(
        10 <= args.vus <= 2000 and args.vus % 10 == 0,
        "VU는 10~2000 범위의 10명 단위여야 합니다.",
    )
    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    ensure(args.vus <= len(users), "회원 수가 부족합니다.")

    pid = (ROOT / ".local/app.pid").read_text().strip()
    ensure(pid.isdigit(), "앱 PID가 올바르지 않습니다.")
    captured([
        "lsof", "-nP", "-a", "-p", pid,
        "-iTCP:18096", "-sTCP:LISTEN",
    ])

    folder = ROOT / "results" / args.run_name
    ensure(not folder.exists(), "같은 이름의 결과 폴더가 있습니다.")
    lock = ROOT / ".local/active-run.json"
    ensure(
        not lock.exists(),
        "이전 실행의 차단 상태가 남아 있습니다. 먼저 결과를 확인하세요.",
    )

    with lock.open("x", encoding="utf-8") as file:
        json.dump({"runName": args.run_name}, file)
    folder.mkdir(parents=True, exist_ok=False)
    print("실행 준비를 시작합니다. 검증 종료 전 다음 실행을 차단합니다.")

    state = load_module("service_state", ROOT / "service-state.py")
    verifier = load_module("service_verify", ROOT / "verify-service.py")
    sampler_source = ROOT.parent / "auth-capacity" / "run-login.py"
    sampler = load_module("service_resources", sampler_source)

    # DB 기준 준비를 먼저 끝내고 측정 직전에 토큰을 발급한다.
    run_tool("prepare-fixture.py", "--users", str(args.vus))
    fixture_path = ROOT / ".local/service-fixture.json"
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    before = state.snapshot()

    ensure(
        all(
            row["orders"] == 100
            and row["rows"] == 100
            and row["invalidRows"] == 0
            for row in before["history"]
        ),
        "조회용 이력 상태 확인이 필요합니다.",
    )
    ensure(
        all(
            row["status"] == "CANCELLED"
            and row["paymentStatus"] == "CANCELLED"
            for row in before["writeOrders"]
        ),
        "이전 실행의 미취소 주문 또는 결제가 있습니다.",
    )
    save(folder / "db-before.json", before)
    save(folder / "fixture.json", fixture)

    run_tool("prepare-tokens.py", "--users", str(args.vus))
    account_path = ROOT / ".local/accounts.json"
    account_hash = hashlib.sha256(account_path.read_bytes()).hexdigest()

    sources = [
        "application-perf.yaml",
        "start-app.py",
        "prepare-users.py",
        "prepare-history.py",
        "prepare-products.sql",
        "prepare-tokens.py",
        "prepare-fixture.py",
        "service-capacity.js",
        "service-state.py",
        "verify-service.py",
        "run-service.py",
    ]
    for name in sources:
        shutil.copy2(ROOT / name, folder / name)
    shutil.copy2(sampler_source, folder / "resource-sampler.py")

    commit = captured(["git", "rev-parse", "HEAD"])
    (folder / "source-commit.txt").write_text(commit + "\n")
    patch = captured([
        "git", "diff", "HEAD", "--", "src/main/java", "src/test/java",
    ])
    (folder / "source-change.patch").write_text(patch + "\n")

    jar = REPO / "build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar"
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    (folder / "app-jar-sha256.txt").write_text(
        jar_hash + "  " + jar.name + "\n"
    )

    visit_date = (
        datetime.now(timezone(timedelta(hours=9))).date()
        + timedelta(days=1)
    ).isoformat()
    container = "perf-service-" + args.run_name
    metadata = {
        "runName": args.run_name,
        "mode": args.mode,
        "vus": args.vus,
        "duration": args.duration if args.mode == "load" else None,
        "visitDate": visit_date,
        "appPid": int(pid),
        "sourceCommit": commit,
        "jarSha256": jar_hash,
        "accountFileSha256": account_hash,
        "k6Image": IMAGE,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "userAllocation": {
            "orderList": 70,
            "userMe": 20,
            "orderWrite": 10,
        },
    }
    metadata["jfrEnabled"] = args.jfr
    save(folder / "run.json", metadata)

    command = [
        "docker", "run", "--rm", "--name", container,
        "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{REPO / 'perf'}:/scripts:ro",
        "-v", f"{folder}:/results",
        "-e", f"MODE={args.mode}",
        "-e", f"VUS={args.vus}",
        "-e", f"DURATION={args.duration}",
        IMAGE,
        "run",
        "--out", "json=/results/metrics.json",
        "/scripts/service-capacity/service-capacity.js",
    ]
    print(f"통합 부하 시작: {args.vus} VU / {args.mode}")
    print(f"결과 폴더: {folder}")

    if args.jfr:
        mysql_query = (
            "SHOW GLOBAL STATUS WHERE Variable_name IN "
            "('Innodb_row_lock_waits','Innodb_row_lock_time','Uptime');"
        )
        (folder / "mysql-before.txt").write_text(
            state.helper.sql(mysql_query) + "\n",
            encoding="utf-8",
        )
        recording = captured([
            str(jcmd), pid, "JFR.start",
            "name=service-diagnostic",
            "settings=profile",
            "disk=true",
            "maxsize=128m",
            f"filename={folder / 'app-profile.jfr'}",
        ])
        (folder / "jfr-start.log").write_text(
            recording + "\n", encoding="utf-8"
        )
        ensure(
            "Started recording" in recording,
            "Java 진단 기록을 시작하지 못했습니다.",
        )
        print("Java 진단 기록 시작: 로그인 준비 구간 제외")

    stop = threading.Event()
    with (folder / "console.log").open("x", encoding="utf-8") as output:
        process = subprocess.Popen(
            command,
            cwd=REPO,
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=subprocess.STDOUT,
        )
        monitor = threading.Thread(
            target=sampler.sample_resources,
            args=(folder, pid, container, stop),
            daemon=True,
        )
        monitor.start()

        try:
            exit_code = process.wait()
        except KeyboardInterrupt:
            subprocess.run(
                ["docker", "stop", "--time", "5", container],
                stdin=subprocess.DEVNULL,
                capture_output=True,
                timeout=20,
            )
            process.wait(timeout=30)
            raise
        finally:
            stop.set()
            monitor.join(timeout=40)
            if args.jfr:
                result = subprocess.run(
                    [
                        str(jcmd), pid, "JFR.stop",
                        "name=service-diagnostic",
                        f"filename={folder / 'app-profile.jfr'}",
                    ],
                    stdin=subprocess.DEVNULL,
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                (folder / "jfr-stop.log").write_text(
                    result.stdout + result.stderr,
                    encoding="utf-8",
                )
                (folder / "mysql-after.txt").write_text(
                    state.helper.sql(mysql_query) + "\n",
                    encoding="utf-8",
                )
                ensure(
                    result.returncode == 0
                    and (folder / "app-profile.jfr").is_file(),
                    "Java 진단 기록 저장 상태를 확인하세요.",
                )
                print("Java 진단 기록 저장 완료")

    (folder / "exit-code.txt").write_text(str(exit_code) + "\n")
    metadata["finishedAt"] = datetime.now(timezone.utc).isoformat()
    save(folder / "run.json", metadata)

    ensure(
        hashlib.sha256(account_path.read_bytes()).hexdigest() == account_hash,
        "실행 중 인증 정보 파일이 변경됐습니다.",
    )
    report = verifier.verify(folder)

    if report["correctnessPassed"]:
        lock.unlink()
        print("정확성 검증이 끝났고 다음 실행이 가능합니다.")

    metrics = json.loads(
        (folder / "summary.json").read_text(encoding="utf-8")
    )["metrics"]

    for name, label in [
        ("service_list_http", "주문 목록"),
        ("service_me_http", "내 정보"),
        ("service_create_http", "주문 생성"),
        ("service_cancel_http", "주문 취소"),
    ]:
        values = metrics[name]["values"]
        print(
            f"{label}: 평균 {values['avg']:.2f}ms / "
            f"P95 {values['p(95)']:.2f}ms / "
            f"P99 {values['p(99)']:.2f}ms"
        )

    print("토큰·쿠키·비밀번호 원문은 결과 폴더에 저장하지 않았습니다.")

    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("실행이 중단됐습니다. 결과와 실행 차단 상태를 확인하세요.")
        print("차단 파일을 임의로 삭제하거나 같은 부하를 다시 보내지 마세요.")
        raise
