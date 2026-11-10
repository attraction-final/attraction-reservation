"""주문 부하 실행과 결과 보관 및 DB 정확성 대조."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
IMAGE = (
    "grafana/k6@sha256:"
    "3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e"
)

spec = importlib.util.spec_from_file_location(
    "capacity_users", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write_json(path, value):
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def git_output(*arguments):
    return subprocess.check_output(
        ["git", *arguments], cwd=REPO,
        text=True, encoding="utf-8",
    )


def counts():
    return json.loads(helper.sql("""
        SELECT JSON_OBJECT(
            'orders', (SELECT COUNT(*) FROM orders),
            'items', (SELECT COUNT(*) FROM order_items),
            'payments', (SELECT COUNT(*) FROM payments),
            'maxOrderId', (SELECT COALESCE(MAX(id), 0) FROM orders),
            'pending', (
                SELECT COUNT(*) FROM orders WHERE status = 'PENDING'
            )
        );
    """))


def main():
    parser = argparse.ArgumentParser(
        description="주문 생성·취소 실행과 DB 검증 결과를 보관합니다."
    )
    parser.add_argument("run_name")
    parser.add_argument("--vus", type=int, required=True)
    parser.add_argument(
        "--distribution", choices=["shared", "split"], required=True
    )
    parser.add_argument(
        "--mode", choices=["smoke", "load"], default="smoke"
    )
    parser.add_argument(
        "--jfr", action="store_true",
        help="로그인 준비 후 부하 실행 구간의 Java 진단 기록 저장",
    )
    args = parser.parse_args()

    require(
        re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.run_name),
        "결과 이름은 영문·숫자·밑줄·하이픈으로 지정하세요.",
    )
    require(1 <= args.vus <= 100, "회원 수는 1~100명이어야 합니다.")
    require(
        helper.sql("SELECT DATABASE();")
        == "attraction_order_write_capacity_perf",
        "주문 실험 전용 DB가 아닙니다.",
    )

    folder = ROOT / "results" / args.run_name
    folder.mkdir(parents=True, exist_ok=False)

    report = {
        "passed": False,
        "mode": args.mode,
        "distribution": args.distribution,
        "vus": args.vus,
    }

    try:
        for name in [
            "application-perf.yaml", "order-write.js",
            "prepare-load.py", "prepare-users.py",
            "start-app.py", "run-load.py",
        ]:
            (folder / name).write_bytes((ROOT / name).read_bytes())

        (folder / "source-commit.txt").write_text(
            git_output("rev-parse", "HEAD"), encoding="utf-8"
        )
        (folder / "source-change.patch").write_text(
            git_output(
                "diff", "HEAD", "--", "src/main/java", "src/test/java"
            ),
            encoding="utf-8",
        )

        jar = REPO / "build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar"
        require(jar.is_file(), "실행 파일이 없습니다. 먼저 빌드하세요.")
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        (folder / "app-jar-sha256.txt").write_text(
            digest + "\n", encoding="utf-8"
        )

        # 로그인 준비는 부하 측정 시간에 포함하지 않는다.
        subprocess.run(
            [sys.executable, str(ROOT / "prepare-load.py")],
            cwd=REPO, check=True,
        )

        accounts = json.loads(
            (ROOT / ".local/load-accounts.json").read_text(
                encoding="utf-8"
            )
        )[:args.vus]
        require(len(accounts) == args.vus, "준비된 회원이 부족합니다.")

        key = (
            "sharedProductId"
            if args.distribution == "shared"
            else "splitProductId"
        )
        assigned = {
            account["userId"]: account[key] for account in accounts
        }
        require(len(assigned) == args.vus, "배정 회원이 중복됩니다.")
        before = counts()
        require(before["pending"] == 0, "미취소 주문이 남아 있습니다.")

        kst = timezone(timedelta(hours=9))
        visit_date = (
            datetime.now(kst).date() + timedelta(days=1)
        ).isoformat()

        write_json(folder / "fixture.json", {
            "before": before,
            "assignment": assigned,
            "visitDate": visit_date,
            "price": 10000,
            "quantity": 1,
            "initialStock": 10000,
        })

        print(
            f"주문 측정 시작: {args.distribution} / "
            f"{args.vus}명 / {args.mode}",
            flush=True,
        )
        print(f"결과 폴더: {folder}", flush=True)

        command = [
            "docker", "run", "--rm",
            "--user",
            subprocess.check_output(
                ["id", "-u"], text=True
            ).strip() + ":" + subprocess.check_output(
                ["id", "-g"], text=True
            ).strip(),
            "-v", f"{REPO / 'perf'}:/scripts:ro",
            "-v", f"{folder}:/results",
            "-e", f"MODE={args.mode}",
            "-e", f"VUS={args.vus}",
            "-e", f"DISTRIBUTION={args.distribution}",
            IMAGE, "run",
            "--out", "json=/results/metrics.json",
            "/scripts/order-write-capacity/order-write.js",
        ]

        jcmd = None
        pid = None

        if args.jfr:
            java_home = os.environ.get("JAVA_HOME")
            require(bool(java_home), "JAVA_HOME을 설정해 주세요.")
            jcmd = Path(java_home) / "bin/jcmd"
            require(jcmd.is_file(), "Java 진단 도구를 찾지 못했습니다.")
            pid = (ROOT / ".local/app.pid").read_text(
                encoding="utf-8"
            ).strip()
            require(pid.isdigit(), "앱 PID 기록을 확인하세요.")

            report["mysqlLockBefore"] = helper.sql(
                "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';"
            )
            with (folder / "jfr-start.log").open(
                "w", encoding="utf-8"
            ) as log:
                subprocess.run(
                    [
                        str(jcmd), pid, "JFR.start",
                        "name=order_capacity", "settings=profile",
                    ],
                    stdout=log, stderr=subprocess.STDOUT, check=True,
                )
            print("Java 진단 기록 시작: 로그인 준비 구간 제외", flush=True)

        started = time.monotonic()
        report["loadStartedAt"] = datetime.now(
            timezone.utc
        ).isoformat()

        try:
            with (folder / "console.log").open(
                "w", encoding="utf-8"
            ) as console:
                result = subprocess.run(
                    command, cwd=REPO,
                    stdout=console, stderr=subprocess.STDOUT,
                )
        finally:
            report["elapsedSeconds"] = time.monotonic() - started
            report["loadFinishedAt"] = datetime.now(
                timezone.utc
            ).isoformat()

            if args.jfr:
                with (folder / "jfr-stop.log").open(
                    "w", encoding="utf-8"
                ) as log:
                    subprocess.run(
                        [
                            str(jcmd), pid, "JFR.stop",
                            "name=order_capacity",
                            f"filename={folder / 'app-profile.jfr'}",
                        ],
                        stdout=log, stderr=subprocess.STDOUT,
                        check=True,
                    )
                report["mysqlLockAfter"] = helper.sql(
                    "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';"
                )
                print("Java 진단 기록 저장 완료", flush=True)
        report["exitCode"] = result.returncode
        (folder / "exit-code.txt").write_text(
            str(result.returncode) + "\n", encoding="utf-8"
        )

        # k6가 실패해도 먼저 DB 상태를 확인하고 기록한다.
        after = counts()
        report["before"] = before
        report["after"] = after

        rows = [
            json.loads(line)
            for line in helper.sql(f"""
                SELECT JSON_OBJECT(
                    'id', o.id,
                    'userId', o.user_id,
                    'status', o.status,
                    'amount', o.total_amount,
                    'visitDate', DATE_FORMAT(o.visit_date, '%Y-%m-%d'),
                    'itemCount', (
                        SELECT COUNT(*) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'quantity', (
                        SELECT SUM(quantity) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'price', (
                        SELECT MAX(price) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'productId', (
                        SELECT MAX(ticket_product_id) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'paymentCount', (
                        SELECT COUNT(*) FROM payments WHERE order_id = o.id
                    ),
                    'paymentStatus', (
                        SELECT MAX(status) FROM payments WHERE order_id = o.id
                    ),
                    'paymentAmount', (
                        SELECT MAX(amount) FROM payments WHERE order_id = o.id
                    )
                )
                FROM orders o
                WHERE o.id > {int(before["maxOrderId"])}
                ORDER BY o.id;
            """).splitlines()
        ]

        invalid_orders = [
            row["id"] for row in rows
            if not (
                row["userId"] in assigned
                and row["productId"] == assigned[row["userId"]]
                and row["status"] == "CANCELLED"
                and row["amount"] == 10000
                and row["visitDate"] == visit_date
                and row["itemCount"] == 1
                and row["quantity"] == 1
                and row["price"] == 10000
                and row["paymentCount"] == 1
                and row["paymentStatus"] == "CANCELLED"
                and row["paymentAmount"] == 10000
            )
        ]
        report["newOrders"] = len(rows)
        report["invalidOrderCount"] = len(invalid_orders)
        report["invalidOrderExamples"] = invalid_orders[:10]

        product_ids = ",".join(
            str(product_id)
            for product_id in sorted(set(assigned.values()))
        )
        invalid_products = int(helper.sql(f"""
            SELECT COUNT(*) FROM ticket_products
            WHERE id IN ({product_ids})
              AND (stock <> 10000 OR status <> 'ON_SALE');
        """))
        report["invalidProductCount"] = invalid_products

        require(result.returncode == 0, "k6 실행 실패. console.log를 확인하세요.")
        metrics = json.loads(
            (folder / "summary.json").read_text(encoding="utf-8")
        )["metrics"]

        names = [
            "order_create_attempts", "order_created",
            "order_cancel_attempts", "order_cancelled",
            "order_flow_completed", "iterations",
        ]
        counters = {
            name: metrics[name]["values"]["count"] for name in names
        }
        report["counters"] = counters
        completed = counters["order_flow_completed"]

        require(
            completed > 0
            and all(value == completed for value in counters.values()),
            "주문 생성·취소·완료·반복 건수가 다릅니다.",
        )
        if args.mode == "smoke":
            require(
                completed == args.vus * 3,
                "소규모 검사 반복 건수가 다릅니다.",
            )
        require(
            metrics["checks"]["values"]["fails"] == 0
            and metrics["checks"]["values"]["passes"] == completed * 11,
            "응답 검증 건수가 다르거나 실패가 있습니다.",
        )
        require(
            metrics["http_req_failed"]["values"]["rate"] == 0
            and metrics["order_flow_failed"]["values"]["rate"] == 0,
            "HTTP 또는 주문 흐름 실패가 있습니다.",
        )
        console_text = (folder / "console.log").read_text(
            encoding="utf-8"
        )
        finished = re.findall(
            r"(\d+) complete and (\d+) interrupted iterations",
            console_text,
        )
        require(
            bool(finished)
            and int(finished[-1][0]) == completed
            and int(finished[-1][1]) == 0,
            "완료·중단 반복 수를 확인하세요.",
        )
        require(
            len(rows) == completed
            and all(
                after[name] - before[name] == completed
                for name in ["orders", "items", "payments"]
            ),
            "새 주문·항목·결제 수가 완료 건수와 다릅니다.",
        )
        require(
            not invalid_orders
            and invalid_products == 0
            and after["pending"] == 0,
            "주문 소유자·상품·금액·상태·재고 검증에 실패했습니다.",
        )

        report["passed"] = True
        print(f"주문 생성·취소: 각각 {int(completed)}건 성공")
        print(f"응답 검증: {int(completed * 11)}개 통과")
        print("DB 검증: 회원별 상품·금액·수량·방문일 일치")
        print("주문·결제 CANCELLED / 재고 10000 복구 / 미취소 주문 0건")
        for label, name in [
            ("생성 HTTP", "order_create_duration"),
            ("취소 HTTP", "order_cancel_duration"),
            ("전체 흐름", "order_flow_duration"),
        ]:
            values = metrics[name]["values"]
            print(
                f"{label}: 평균 {values['avg']:.2f}ms / "
                f"P95 {values['p(95)']:.2f}ms / "
                f"P99 {values['p(99)']:.2f}ms"
            )
        print("HTTP 실패 및 중단 반복: 0건")
        print("토큰·비밀번호 원문은 결과 폴더에 저장하지 않았습니다.")
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        write_json(folder / "verification.json", report)


if __name__ == "__main__":
    main()
