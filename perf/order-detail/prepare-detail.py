"""개인 DB에서 상세 조회 응답의 검증 기준을 읽어 저장한다."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent

# 기존 개인 테스트 DB 접속 함수를 재사용한다.
sys.path.insert(0, str(ROOT.parent / "order-list"))
from prepare import sql

QUERY = """
SELECT JSON_OBJECT(
    'userId', o.user_id,
    'orderId', o.id,
    'totalAmount', o.total_amount,
    'status', o.status,
    'items', JSON_ARRAYAGG(JSON_OBJECT(
        'id', oi.id,
        'ticketProductId', oi.ticket_product_id,
        'price', oi.price,
        'quantity', oi.quantity
    ))
)
FROM orders AS o
JOIN users AS u ON u.id = o.user_id
JOIN order_items AS oi ON oi.order_id = o.id
WHERE u.email REGEXP '^perf[0-9]{4}@example[.]test$'
  AND EXISTS (
      SELECT 1
      FROM order_items AS selected_item
      JOIN ticket_products AS p
          ON p.id = selected_item.ticket_product_id
      WHERE selected_item.order_id = o.id
        AND p.name IN (
            'PERF_DETAIL_10000',
            'PERF_DETAIL_2000',
            'PERF_DETAIL_3000',
            'PERF_DETAIL_5000'
        )
  )
GROUP BY o.id, o.user_id, o.total_amount, o.status
ORDER BY o.user_id, o.id;
"""


def main():
    if sql("SELECT DATABASE();") != "attraction_perf":
        raise RuntimeError("개인 테스트 DB가 아닙니다.")

    rows = sql(QUERY)
    orders = [json.loads(line) for line in rows.splitlines()]
    by_user = {}

    for order in orders:
        items = order["items"]
        item_total = sum(
            item["price"] * item["quantity"] for item in items
        )

        if order["totalAmount"] != 10000 or item_total != 10000:
            raise RuntimeError("주문 금액과 주문 항목 합계가 예상과 다릅니다.")

        if order["status"] != "COMPLETED":
            raise RuntimeError("예상하지 않은 주문 상태입니다.")

        if len(items) not in (1, 3):
            raise RuntimeError("주문별 상품 종류 수가 예상과 다릅니다.")

        if any(item["quantity"] != 1 for item in items):
            raise RuntimeError("주문 항목 수량이 예상과 다릅니다.")

        by_user.setdefault(order["userId"], []).append(order)

    if len(by_user) != 100:
        raise RuntimeError("테스트 회원 수가 100명이 아닙니다.")

    for user_orders in by_user.values():
        if sorted(len(order["items"]) for order in user_orders) != [1, 3]:
            raise RuntimeError("회원별 상품 1종·3종 주문 구성이 다릅니다.")

    local = ROOT / ".local"
    local.mkdir(parents=True, exist_ok=True)
    target = local / "detail-fixture.json"
    target.write_text(
        json.dumps(orders, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(
        f"검증 기준 준비 완료: 회원 {len(by_user)}명 / "
        f"주문 {len(orders)}건 / "
        f"주문 항목 {sum(len(o['items']) for o in orders)}건"
    )
    print(f"저장 위치: {target}")


if __name__ == "__main__":
    main()