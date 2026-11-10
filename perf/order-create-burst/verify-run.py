"""집중 요청의 응답 기록과 DB 상태를 대조한다."""
import argparse
from collections import Counter
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


def save_json(path, value):
    with path.open("x", encoding="utf-8") as file:
        json.dump(value, file, ensure_ascii=False, indent=2)
        file.write("\n")


def main():
    parser = argparse.ArgumentParser(
        description="주문 응답과 실행 상품의 DB 상태를 대조합니다."
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
    report_path = folder / f"verification-{args.label}.json"
    snapshot_path = folder / f"db-snapshot-{args.label}.json"
    require(
        not report_path.exists() and not snapshot_path.exists(),
        "같은 검증 기록이 있습니다. 다른 --label을 사용하세요.",
    )

    fixture = json.loads(
        (folder / "fixture.json").read_text(encoding="utf-8")
    )
    metrics = json.loads(
        (folder / "summary.json").read_text(encoding="utf-8")
    )["metrics"]
    expected = fixture["users"]
    allowed_users = set(fixture["userIds"])
    product_id = int(fixture["productId"])

    outcomes = []
    with (folder / "metrics.json").open(encoding="utf-8") as file:
        for line in file:
            point = json.loads(line)
            if (
                point.get("type") == "Point"
                and point.get("metric") == "burst_outcomes"
            ):
                tags = point["data"]["tags"]
                outcomes.append({
                    "userId": int(tags["user_id"]),
                    "orderId": int(tags["order_id"]),
                    "httpStatus": int(tags["http_status"]),
                    "valid": tags["valid"] == "true",
                    "errorCode": int(tags["error_code"]),
                })

    # 한 SQL의 일관된 읽기 시점에서 주문·재고·전체 건수를 조회한다.
    snapshot = json.loads(helper.sql(f"""
        SELECT JSON_OBJECT(
            'productId', tp.id,
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
                        SELECT COUNT(*) FROM payments WHERE order_id = o.id
                    ),
                    'paymentStatus', (
                        SELECT MAX(status) FROM payments WHERE order_id = o.id
                    ),
                    'paymentAmount', (
                        SELECT MAX(amount) FROM payments WHERE order_id = o.id
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
    rows = snapshot["orders"]
    by_id = {row["id"]: row for row in rows}

    def count(name):
        return metrics.get(name, {}).get("values", {}).get("count", 0)

    valid_responses = [outcome for outcome in outcomes if outcome["valid"]]
    acknowledged_pairs = {
        (outcome["userId"], outcome["orderId"])
        for outcome in valid_responses
    }
    mismatched_responses = [
        outcome for outcome in valid_responses
        if (
            outcome["orderId"] not in by_id
            or by_id[outcome["orderId"]]["userId"] != outcome["userId"]
        )
    ]
    unacknowledged_orders = [
        row["id"] for row in rows
        if (row["userId"], row["id"]) not in acknowledged_pairs
    ]

    invalid_orders = [
        row["id"] for row in rows
        if not (
            row["userId"] in allowed_users
            and row["status"] == "PENDING"
            and row["amount"] == fixture["price"] * fixture["quantity"]
            and row["visitDate"] == fixture["visitDate"]
            and row["itemCount"] == 1
            and row["productId"] == product_id
            and row["quantity"] == fixture["quantity"]
            and row["price"] == fixture["price"]
            and row["paymentCount"] == 1
            and row["paymentStatus"] == "PENDING"
            and row["paymentAmount"] == row["amount"]
        )
    ]

    owners = Counter(row["userId"] for row in rows)
    db_consistent = (
        not invalid_orders
        and all(number == 1 for number in owners.values())
        and len(rows) <= expected
        and snapshot["price"] == fixture["price"]
        and snapshot["stock"]
        == fixture["initialStock"] - len(rows) * fixture["quantity"]
        and snapshot["status"] == "ON_SALE"
        and all(
            snapshot["totals"][name] - fixture["before"][name] == len(rows)
            for name in ["orders", "items", "payments"]
        )
    )

    finished = re.findall(
        r"(\d+) complete and (\d+) interrupted iterations",
        (folder / "console.log").read_text(encoding="utf-8"),
    )
    offsets = metrics.get(
        "burst_launch_offset", {}
    ).get("values", {})

    load_valid = (
        count("burst_attempts") == expected
        and count("burst_outcomes") == expected
        and count("iterations") == expected
        and len(outcomes) == expected
        and Counter(outcome["userId"] for outcome in outcomes)
        == Counter(fixture["userIds"])
        and bool(finished)
        and finished[-1] == (str(expected), "0")
        and offsets.get("min", -1) >= 0
        and offsets.get("max", 1000) < 1000
    )

    checks = metrics.get("checks", {}).get("values", {})
    all_successful = (
        count("burst_successes") == expected
        and len(valid_responses) == expected
        and checks.get("passes") == expected * 7
        and checks.get("fails") == 0
        and metrics.get(
            "http_req_failed", {}
        ).get("values", {}).get("rate") == 0
        and int((folder / "exit-code.txt").read_text().strip()) == 0
    )

    passed = (
        load_valid and all_successful and db_consistent
        and len(rows) == expected
        and not mismatched_responses
        and not unacknowledged_orders
    )

    report = {
        "runName": args.run_name,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "passed": passed,
        "loadValid": load_valid,
        "allResponsesSuccessful": all_successful,
        "dbSnapshotConsistent": db_consistent,
        "expectedUsers": expected,
        "recordedOutcomes": len(outcomes),
        "validResponses": len(valid_responses),
        "httpStatuses": dict(Counter(
            outcome["httpStatus"] for outcome in outcomes
        )),
        "errorCodes": dict(Counter(
            outcome["errorCode"] for outcome in outcomes
        )),
        "dbOrders": len(rows),
        "remainingStock": snapshot["stock"],
        "invalidOrderIds": invalid_orders,
        "mismatchedResponses": mismatched_responses,
        "ordersWithoutValidResponse": unacknowledged_orders,
    }
    report["note"] = (
        "실패·시간 초과가 있으면 처리 중인 요청이 남을 수 있습니다. "
        "이 DB 관측만으로 처리가 끝났다고 확정하지 않습니다."
    )

    save_json(snapshot_path, snapshot)
    save_json(report_path, report)

    print(f"집중 요청 검증: {args.run_name}")
    print(f"부하 조건 충족: {load_valid}")
    print(f"응답 전체 성공: {all_successful}")
    print(f"DB 관측 정합성: {db_consistent}")
    print(f"정상 응답: {len(valid_responses)}/{expected}건")
    print(f"실제 주문: {len(rows)}건 / 남은 재고: {snapshot['stock']}개")
    print(f"유효한 성공 응답 없이 저장된 주문: {len(unacknowledged_orders)}건")
    print(f"최종 검증 통과: {passed}")
    print("아직 주문을 취소하지 않았습니다. 다음 실행 차단도 유지합니다.")

    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
