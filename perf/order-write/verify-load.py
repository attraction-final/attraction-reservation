"""저장된 k6 결과와 전용 상품의 DB 상태를 대조한다."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "order-list"))

from prepare import sql


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_id")
    args = parser.parse_args()

    require(
        re.fullmatch(r"[a-zA-Z0-9_-]{1,60}", args.run_id),
        "실행 ID 형식이 올바르지 않습니다.",
    )
    require(
        sql("SELECT DATABASE();") == "attraction_perf",
        "개인 테스트 DB가 아닙니다.",
    )

    folder = ROOT / "results" / args.run_id
    fixture = json.loads(
        (folder / "fixture.json").read_text(encoding="utf-8")
    )
    summary = json.loads(
        (folder / "summary.json").read_text(encoding="utf-8")
    )
    metrics = summary["metrics"]

    report = {
        "runId": args.run_id,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "passed": False,
    }

    try:
        counters = {
            name: metrics[name]["values"]["count"]
            for name in [
                "order_create_attempts",
                "order_created",
                "order_cancel_attempts",
                "order_cancelled",
                "order_flow_completed",
                "iterations",
            ]
        }
        report["counters"] = counters
        completed = int(counters["order_flow_completed"])

        require(completed > 0, "완료된 흐름이 없습니다.")
        require(
            all(value == completed for value in counters.values()),
            "생성·취소·완료·반복 건수가 일치하지 않습니다.",
        )
        require(
            metrics["checks"]["values"]["fails"] == 0
            and metrics["checks"]["values"]["passes"] == completed * 11,
            "응답 검증 결과가 예상과 다릅니다.",
        )
        require(
            metrics["http_req_failed"]["values"]["rate"] == 0
            and metrics["order_flow_failed"]["values"]["rate"] == 0,
            "HTTP 또는 전체 흐름 실패가 있습니다.",
        )

        vus = int(metrics["vus_max"]["values"]["max"])
        require(
            1 <= vus <= len(fixture["userIds"]),
            "VU 수와 검증 기준이 맞지 않습니다.",
        )
        allowed_users = set(fixture["userIds"][:vus])

        product_id = int(fixture["productId"])
        last_id = int(fixture["before"]["maxOrderId"])
        price = int(fixture["price"])
        quantity = int(fixture["quantity"])
        amount = price * quantity

        product = json.loads(sql(f"""
            SELECT JSON_OBJECT(
                'stock', stock,
                'status', status,
                'version', version
            )
            FROM ticket_products
            WHERE id = {product_id}
              AND name = 'PERF_ORDER_WRITE_LOAD';
        """))
        report["product"] = product

        rows = [
            json.loads(line)
            for line in sql(f"""
                SELECT JSON_OBJECT(
                    'orderId', o.id,
                    'userId', o.user_id,
                    'status', o.status,
                    'totalAmount', o.total_amount,
                    'visitDate', DATE_FORMAT(o.visit_date, '%Y-%m-%d'),
                    'itemCount', (
                        SELECT COUNT(*) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'wrongItems', (
                        SELECT COUNT(*) FROM order_items
                        WHERE order_id = o.id
                          AND (
                              ticket_product_id <> {product_id}
                              OR price <> {price}
                              OR quantity <> {quantity}
                          )
                    ),
                    'paymentCount', (
                        SELECT COUNT(*) FROM payments
                        WHERE order_id = o.id
                    ),
                    'validCancelledPayments', (
                        SELECT COUNT(*) FROM payments
                        WHERE order_id = o.id
                          AND status = 'CANCELLED'
                          AND amount = {amount}
                    )
                )
                FROM orders o
                WHERE o.id > {last_id}
                  AND EXISTS (
                      SELECT 1 FROM order_items oi
                      WHERE oi.order_id = o.id
                        AND oi.ticket_product_id = {product_id}
                  )
                ORDER BY o.id;
            """).splitlines()
        ]
        report["newOrders"] = rows

        require(
            len(rows) == completed
            and len({row["orderId"] for row in rows}) == completed,
            "이번 실행의 DB 주문 수가 k6 완료 건수와 다릅니다.",
        )

        for row in rows:
            require(
                row["userId"] in allowed_users
                and row["status"] == "CANCELLED"
                and row["totalAmount"] == amount
                and row["visitDate"] == fixture["visitDate"]
                and row["itemCount"] == 1
                and row["wrongItems"] == 0
                and row["paymentCount"] == 1
                and row["validCancelledPayments"] == 1,
                f"주문 {row['orderId']}의 DB 상태가 예상과 다릅니다.",
            )

        require(
            product["stock"] == fixture["initialStock"]
            and product["status"] == "ON_SALE",
            "상품 재고 또는 판매 상태가 복구되지 않았습니다.",
        )

        report["passed"] = True

        print(f"k6 생성·취소·완료 건수 일치: 각각 {completed}건")
        print(f"DB 신규 주문·항목·결제 일치: 각각 {len(rows)}건")
        print("주문·결제 CANCELLED 및 주문자·금액·수량·방문일 확인")
        print(f"최종 재고: {product['stock']}개 / 상태: {product['status']}")
        print("실행 후 DB 검증 통과")
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        (folder / "verification.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    print("검증 결과:", folder / "verification.json")


if __name__ == "__main__":
    main()