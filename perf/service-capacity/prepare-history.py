"""조회용 취소 주문 이력을 준비하고 관계와 금액을 확인한다."""
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "service_users", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
COUNT = 100


def ensure(condition, message):
    if not condition:
        raise RuntimeError(message)


def read_history(user_id):
    return json.loads(helper.sql(f"""
        SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT(
            'orderId', o.id,
            'userId', o.user_id,
            'status', o.status,
            'totalAmount', o.total_amount,
            'visitDate', DATE_FORMAT(o.visit_date, '%Y-%m-%d'),
            'paymentStatus', p.status,
            'paymentAmount', p.amount,
            'paidAt', p.paid_at,
            'productId', i.ticket_product_id,
            'price', i.price,
            'quantity', i.quantity
        )), JSON_ARRAY())
        FROM orders o
        JOIN payments p ON p.order_id = o.id
        LEFT JOIN order_items i ON i.order_id = o.id
        WHERE p.pg_id LIKE 'perf-svc-history-{user_id}-%';
    """))


def main():
    ensure(
        helper.sql("SELECT DATABASE();")
        == "attraction_service_capacity_perf",
        "통합 실험 전용 DB가 아닙니다.",
    )
    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    ensure(
        users and len({u["id"] for u in users}) == len(users),
        "회원 검증 기준이 없거나 중복됐습니다.",
    )

    product_rows = helper.sql("""
        SELECT id, price, stock, status
        FROM ticket_products
        WHERE name = 'PERF_SERVICE_HISTORY';
    """).splitlines()
    ensure(len(product_rows) == 1, "조회용 상품이 하나가 아닙니다.")

    product_id, price, stock, status = product_rows[0].split("\t")
    product_id = int(product_id)
    ensure(
        (price, stock, status) == ("10000", "1000000", "ON_SALE"),
        "조회용 상품 상태가 예상과 다릅니다.",
    )

    fixture = []
    created_users = 0

    for number, user in enumerate(users, 1):
        uid = int(user["id"])
        ensure(uid > 0, "회원 ID가 올바르지 않습니다.")
        rows = read_history(uid)

        if not rows:
            # 기존 주문이 있는 회원에게 이력을 임의로 추가하지 않는다.
            ensure(
                helper.sql(
                    f"SELECT COUNT(*) FROM orders WHERE user_id={uid};"
                ) == "0",
                f"회원 {uid}: 기존 주문이 있어 준비를 중단합니다.",
            )

            statements = ["START TRANSACTION;"]

            for index in range(COUNT):
                statements.append(f"""
                    INSERT INTO orders (
                        user_id, total_amount, status, visit_date,
                        created_at, updated_at
                    ) VALUES (
                        {uid}, 10000, 'CANCELLED', '2025-01-01',
                        TIMESTAMPADD(
                            SECOND, {index}, '2025-01-01 00:00:00'
                        ),
                        TIMESTAMPADD(
                            SECOND, {index}, '2025-01-01 00:00:00'
                        )
                    );

                    SET @history_order_id = LAST_INSERT_ID();

                    INSERT INTO order_items (
                        order_id, ticket_product_id, price, quantity
                    ) VALUES (
                        @history_order_id, {product_id}, 10000, 1
                    );

                    INSERT INTO payments (
                        order_id, pg_id, amount, status, paid_at,
                        created_at, updated_at
                    ) VALUES (
                        @history_order_id,
                        'perf-svc-history-{uid}-{index}',
                        10000, 'CANCELLED', NULL,
                        TIMESTAMPADD(
                            SECOND, {index}, '2025-01-01 00:00:00'
                        ),
                        TIMESTAMPADD(
                            SECOND, {index}, '2025-01-01 00:00:00'
                        )
                    );
                """)

            statements.append("COMMIT;")
            helper.sql("\n".join(statements))
            created_users += 1
            rows = read_history(uid)

        ensure(
            len(rows) == COUNT
            and len({row["orderId"] for row in rows}) == COUNT
            and all(
                row["userId"] == uid
                and row["status"] == "CANCELLED"
                and row["totalAmount"] == 10000
                and row["visitDate"] == "2025-01-01"
                and row["paymentStatus"] == "CANCELLED"
                and row["paymentAmount"] == 10000
                and row["paidAt"] is None
                and row["productId"] == product_id
                and row["price"] == 10000
                and row["quantity"] == 1
                for row in rows
            ),
            f"회원 {uid}: 조회용 주문·항목·결제 검증 실패",
        )

        fixture.append({
            "userId": uid,
            "orderIds": sorted(row["orderId"] for row in rows),
            "count": COUNT,
        })

        if number % 100 == 0 or number == len(users):
            print(
                f"조회 이력 준비: {number}/{len(users)}명",
                flush=True,
            )

    ensure(
        helper.sql(
            f"SELECT stock FROM ticket_products WHERE id={product_id};"
        ) == stock,
        "조회용 상품 재고가 변경됐습니다.",
    )

    (ROOT / ".local/history.json").write_text(
        json.dumps(fixture, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(f"조회 이력 검증 완료: {len(users)}명 × {COUNT}건")
    print(f"신규 이력 준비 회원: {created_users}명")
    print("주문·항목·결제 관계, 소유자·금액·수량·취소 상태 확인")
    print("상품 재고 변경 없음 / 인증 정보 저장 없음")
    print("조회 데이터 준비이며 실제 주문 API 부하 측정이 아닙니다.")


if __name__ == "__main__":
    main()
