"""통합 실험 DB의 일관된 읽기 전용 상태를 조회한다."""
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "service_users", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def snapshot():
    if helper.sql("SELECT DATABASE();") != "attraction_service_capacity_perf":
        raise RuntimeError("통합 실험 전용 DB가 아닙니다.")

    query = """
    SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
    START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;

    SELECT JSON_OBJECT(
        'users', (SELECT COUNT(*) FROM users),
        'orders', (SELECT COUNT(*) FROM orders),
        'items', (SELECT COUNT(*) FROM order_items),
        'payments', (SELECT COUNT(*) FROM payments)
    );

    SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
        'id', id,
        'name', name,
        'price', price,
        'stock', stock,
        'status', status
    )), JSON_ARRAY())
    FROM ticket_products
    WHERE name IN ('PERF_SERVICE_HISTORY', 'PERF_SERVICE_WRITE');

    SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
        'userId', h.user_id,
        'orders', h.order_count,
        'rows', h.row_count,
        'invalidRows', h.invalid_count
    )), JSON_ARRAY())
    FROM (
        SELECT
            o.user_id,
            COUNT(DISTINCT o.id) AS order_count,
            COUNT(*) AS row_count,
            SUM(CASE WHEN
                o.status = 'CANCELLED'
                AND o.total_amount = 10000
                AND o.visit_date = '2025-01-01'
                AND p.status = 'CANCELLED'
                AND p.amount = 10000
                AND p.paid_at IS NULL
                AND i.price = 10000
                AND i.quantity = 1
                AND t.name = 'PERF_SERVICE_HISTORY'
                THEN 0 ELSE 1 END) AS invalid_count
        FROM orders o
        JOIN payments p ON p.order_id = o.id
        LEFT JOIN order_items i ON i.order_id = o.id
        LEFT JOIN ticket_products t ON t.id = i.ticket_product_id
        WHERE p.pg_id LIKE 'perf-svc-history-%'
        GROUP BY o.user_id
    ) h;

    SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
        'orderId', o.id,
        'userId', o.user_id,
        'status', o.status,
        'totalAmount', o.total_amount,
        'visitDate', DATE_FORMAT(o.visit_date, '%Y-%m-%d'),
        'itemId', i.id,
        'productId', i.ticket_product_id,
        'price', i.price,
        'quantity', i.quantity,
        'paymentId', p.id,
        'paymentStatus', p.status,
        'paymentAmount', p.amount
    )), JSON_ARRAY())
    FROM orders o
    JOIN order_items i ON i.order_id = o.id
    JOIN ticket_products t ON t.id = i.ticket_product_id
    LEFT JOIN payments p ON p.order_id = o.id
    WHERE t.name = 'PERF_SERVICE_WRITE';

    COMMIT;
    """

    lines = helper.sql(query).splitlines()
    if len(lines) != 4:
        raise RuntimeError("DB 상태 조회 결과의 개수가 다릅니다.")

    counts, products, history, write_orders = map(json.loads, lines)
    return {
        "counts": counts,
        "products": sorted(products, key=lambda row: row["id"]),
        "history": sorted(history, key=lambda row: row["userId"]),
        "writeOrders": sorted(
            write_orders, key=lambda row: (row["orderId"], row["itemId"])
        ),
    }


def main():
    state = snapshot()
    print("전용 DB 상태 조회 완료")
    for name, label in [
        ("users", "회원"),
        ("orders", "주문"),
        ("items", "주문 항목"),
        ("payments", "결제"),
    ]:
        print(f"{label}: {state['counts'][name]}건")

    print(f"조회 이력 보유 회원: {len(state['history'])}명")
    invalid = [
        row["userId"]
        for row in state["history"]
        if row["orders"] != 100
        or row["rows"] != 100
        or row["invalidRows"] != 0
    ]
    if invalid:
        raise RuntimeError(f"조회 이력 확인이 필요한 회원: {invalid}")

    print("회원별 조회 이력 100건 및 주문·항목·결제 조건 확인")
    print(f"생성·취소용 상품의 주문 항목: {len(state['writeOrders'])}건")
    for product in state["products"]:
        print(f"상품 {product['name']}: 재고 {product['stock']}개")
    print("읽기 전용 조회 완료 / DB 변경 없음")


if __name__ == "__main__":
    main()
