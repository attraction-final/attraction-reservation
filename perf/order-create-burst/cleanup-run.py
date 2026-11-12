"""검증을 통과한 실행의 주문을 취소하고 재고 복구를 확인한다."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "burst_users", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(
        description="검증된 실험 주문을 취소하고 재고 복구를 확인합니다."
    )
    parser.add_argument("run_name")
    parser.add_argument("--label", default="initial")
    args = parser.parse_args()

    require(
        re.fullmatch(r"[A-Za-z0-9_-]{1,48}", args.run_name)
        and re.fullmatch(r"[A-Za-z0-9_-]{1,32}", args.label),
        "실행 이름 또는 검증 이름 형식이 다릅니다.",
    )
    require(
        helper.sql("SELECT DATABASE();")
        == "attraction_order_create_burst_perf",
        "주문 집중 요청 전용 DB가 아닙니다.",
    )

    folder = ROOT / "results" / args.run_name
    fixture = read_json(folder / "fixture.json")
    verification = read_json(
        folder / f"verification-{args.label}.json"
    )
    baseline = read_json(
        folder / f"db-snapshot-{args.label}.json"
    )
    active_path = ROOT / ".local/active-run.json"
    active = read_json(active_path)

    require(
        fixture["runName"] == args.run_name
        and verification["runName"] == args.run_name
        and verification["passed"] is True
        and active["runName"] == args.run_name
        and active["productName"] == fixture["productName"],
        "실행 정보가 다르거나 사전 검증을 통과하지 않았습니다.",
    )

    product_id = fixture["productId"]
    require(
        type(product_id) is int and product_id > 0,
        "상품 ID가 올바르지 않습니다.",
    )

    baseline_rows = baseline["orders"]
    original = {row["id"]: row for row in baseline_rows}
    require(
        len(original) == len(baseline_rows) == fixture["users"]
        and baseline["productId"] == product_id
        and all(
            row["status"] == "PENDING"
            and row["paymentStatus"] == "PENDING"
            for row in baseline_rows
        ),
        "사전 DB 기록이 예상과 다릅니다.",
    )

    users = read_json(ROOT / ".local/users.json")
    members = {user["id"]: user for user in users}
    require(
        all(row["userId"] in members for row in baseline_rows),
        "주문 소유자의 회원 정보가 없습니다.",
    )

    def snapshot():
        return json.loads(helper.sql(f"""
            SELECT JSON_OBJECT(
                'productId', tp.id,
                'name', tp.name,
                'price', tp.price,
                'stock', tp.stock,
                'status', tp.status,
                'orders', COALESCE((
                    SELECT JSON_ARRAYAGG(JSON_OBJECT(
                        'id', o.id,
                        'userId', o.user_id,
                        'status', o.status,
                        'amount', o.total_amount,
                        'visitDate', DATE_FORMAT(o.visit_date, '%Y-%m-%d'),
                        'itemCount', (
                            SELECT COUNT(*) FROM order_items
                            WHERE order_id = o.id
                        ),
                        'productId', (
                            SELECT MAX(ticket_product_id) FROM order_items
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
                        'paymentCount', (
                            SELECT COUNT(*) FROM payments
                            WHERE order_id = o.id
                        ),
                        'paymentStatus', (
                            SELECT MAX(status) FROM payments
                            WHERE order_id = o.id
                        ),
                        'paymentAmount', (
                            SELECT MAX(amount) FROM payments
                            WHERE order_id = o.id
                        )
                    ))
                    FROM orders o
                    WHERE EXISTS (
                        SELECT 1 FROM order_items oi
                        WHERE oi.order_id = o.id
                          AND oi.ticket_product_id = {product_id}
                    )
                ), JSON_ARRAY()),
                'totals', JSON_OBJECT(
                    'orders', (SELECT COUNT(*) FROM orders),
                    'items', (SELECT COUNT(*) FROM order_items),
                    'payments', (SELECT COUNT(*) FROM payments)
                )
            )
            FROM ticket_products tp WHERE tp.id = {product_id};
        """))

    def validate(state, finished=False):
        rows = state["orders"]
        require(
            len(rows) == len(original)
            and {row["id"] for row in rows} == set(original),
            "실험 주문 목록이 사전 기록과 다릅니다.",
        )
        pending = 0
        for row in rows:
            expected = dict(original[row["id"]])
            require(
                row["status"] in ("PENDING", "CANCELLED"),
                "정리 대상 주문의 상태가 예상과 다릅니다.",
            )
            expected["status"] = row["status"]
            expected["paymentStatus"] = row["status"]
            require(
                row == expected,
                "주문 소유자·상품·금액·수량 또는 결제 상태가 다릅니다.",
            )
            if row["status"] == "PENDING":
                pending += 1

        require(
            state["productId"] == product_id
            and state["name"] == fixture["productName"]
            and state["price"] == fixture["price"]
            and state["status"] == "ON_SALE"
            and state["stock"]
            == fixture["initialStock"] - pending * fixture["quantity"]
            and state["totals"] == baseline["totals"],
            "상품·재고 또는 전체 이력 건수가 예상과 다릅니다.",
        )
        if finished:
            require(pending == 0, "미취소 주문이 남아 있습니다.")

    report = {
        "runName": args.run_name,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": False,
        "cancelledThisAttempt": 0,
        "alreadyCancelled": 0,
    }
    with (folder / "cleanup-attempts.jsonl").open(
        "a", encoding="utf-8"
    ) as journal:
        def record(value):
            journal.write(json.dumps(value, ensure_ascii=False) + "\n")
            journal.flush()

        record({"event": "시작", **report})
        try:
            state = snapshot()
            validate(state)

            for index, row in enumerate(
                sorted(state["orders"], key=lambda item: item["id"]),
                start=1,
            ):
                if row["status"] == "CANCELLED":
                    report["alreadyCancelled"] += 1
                    continue

                member = members[row["userId"]]
                status, body, _ = helper.request(
                    "POST", "/api/auth/login",
                    {
                        "email": member["email"],
                        "password": helper.PASSWORD,
                    },
                    helper.csrf_headers(),
                )
                data = body.get("data") or {}
                token = data.get("accessToken")
                require(
                    status == 200
                    and body.get("success") is True
                    and data.get("tokenType") == "Bearer"
                    and isinstance(token, str) and bool(token),
                    "취소를 위한 로그인에 실패했습니다.",
                )

                status, body, _ = helper.request(
                    "POST", f"/api/orders/{row['id']}/cancel",
                    headers={"Authorization": f"Bearer {token}"},
                )
                data = body.get("data") or {}
                require(
                    status == 200
                    and body.get("success") is True
                    and data.get("orderId") == row["id"]
                    and data.get("status") == "CANCELLED",
                    "취소 응답이 예상과 다릅니다. DB 확인이 필요합니다.",
                )
                report["cancelledThisAttempt"] += 1
                record({
                    "event": "취소",
                    "orderId": row["id"],
                    "httpStatus": status,
                })
                if index % 100 == 0:
                    print(
                        f"취소 진행: {index}/{len(original)}건",
                        flush=True,
                    )

            final = snapshot()
            validate(final, finished=True)
            (folder / "cleanup-db-snapshot.json").write_text(
                json.dumps(final, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            require(
                read_json(active_path) == active,
                "진행 중인 실행 정보가 변경됐습니다.",
            )
            report["passed"] = True
            report["finishedAt"] = datetime.now(timezone.utc).isoformat()
            record({"event": "완료", **report})
            (folder / "cleanup.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            active_path.unlink()

            print(f"주문·결제 취소 확인: {len(original)}건")
            print(f"재고 복구 확인: {final['stock']}개")
            print("주문·항목·결제 이력 건수 유지 확인")
            print("정리 검증 통과: 다음 실행이 가능합니다.")
            print("토큰과 비밀번호는 결과 파일에 저장하지 않았습니다.")
        except Exception as error:
            record({
                "event": "중단",
                "errorType": type(error).__name__,
            })
            print("정리가 중단됐습니다. 다음 실행 차단을 유지합니다.")
            print("같은 실행의 DB 상태를 확인한 후 이어서 정리하세요.")
            raise


if __name__ == "__main__":
    main()
