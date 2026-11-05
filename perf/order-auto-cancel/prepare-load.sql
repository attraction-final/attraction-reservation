-- 자동 취소 전용 DB의 만료 주문 300건 준비.
USE attraction_auto_cancel_perf;
SET time_zone = '+00:00';

-- 앱을 종료한 상태에서 실행한다.
CREATE TEMPORARY TABLE preparation_guard (
    allowed INT NOT NULL,
    CHECK (allowed = 1)
);

INSERT INTO preparation_guard
SELECT
    (SELECT COUNT(*) FROM orders) = 100006
        AND (SELECT COUNT(*) FROM orders WHERE status = 'PENDING') = 0
        AND (
                SELECT COUNT(*)
                FROM users
                WHERE email = 'perfautocancel0000@example.test'
                  AND status = 'ACTIVE'
            ) = 1
        AND (
                SELECT COUNT(*)
                FROM ticket_products
                WHERE name = 'PERF_AUTO_CANCEL_LOAD_01'
            ) = 0;

-- 주문 300건을 만들기 위한 번호표.
CREATE TEMPORARY TABLE fixture_numbers (
    n INT PRIMARY KEY
);

INSERT INTO fixture_numbers (n)
WITH RECURSIVE numbers AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1
    FROM numbers
    WHERE n < 300
)
SELECT n FROM numbers;

START TRANSACTION;

SET @fixture_now = UTC_TIMESTAMP(6);
SET @visit_date = DATE_ADD(
    DATE(CONVERT_TZ(@fixture_now, '+00:00', '+09:00')),
    INTERVAL 1 DAY
);

SET @fixture_user_id = (
    SELECT id
    FROM users
    WHERE email = 'perfautocancel0000@example.test'
);

SET @previous_max_order_id = (
    SELECT COALESCE(MAX(id), 0)
    FROM orders
);

-- 1,000개 중 300개가 주문으로 차감된 상태.
INSERT INTO ticket_products (
    created_at, updated_at,
    name, description,
    price, stock, status, type, version
)
VALUES (
           @fixture_now, @fixture_now,
           'PERF_AUTO_CANCEL_LOAD_01',
           '자동 취소 반복 측정용 합성 상품',
           10000, 700, 'ON_SALE', 'ALL_DAY', 0
       );

SET @fixture_product_id = LAST_INSERT_ID();

-- 모두 생성 후 60분을 넘긴 결제 대기 주문.
-- 생성 시간을 다르게 해 처리 순서를 구분한다.
INSERT INTO orders (
    created_at, updated_at,
    status, total_amount, visit_date, user_id
)
SELECT
    TIMESTAMPADD(SECOND, -(3600 + n), @fixture_now),
    @fixture_now,
    'PENDING', 10000, @visit_date, @fixture_user_id
FROM fixture_numbers
ORDER BY n DESC;

INSERT INTO order_items (
    price, quantity, order_id, ticket_product_id
)
SELECT
    10000, 1, o.id, @fixture_product_id
FROM orders o
WHERE o.id > @previous_max_order_id
  AND o.user_id = @fixture_user_id;

INSERT INTO payments (
    created_at, updated_at,
    amount, paid_at, pg_id, status, order_id
)
SELECT
    o.created_at, @fixture_now,
    10000, NULL,
    CONCAT('perf-auto-load-01-', LPAD(n.n, 3, '0')),
    'PENDING', o.id
FROM orders o
         JOIN fixture_numbers n
              ON o.created_at =
                 TIMESTAMPADD(SECOND, -(3600 + n.n), @fixture_now)
WHERE o.id > @previous_max_order_id
  AND o.user_id = @fixture_user_id;

COMMIT;

SELECT
    o.status AS order_status,
    p.status AS payment_status,
    COUNT(*) AS fixture_count,
    SUM(oi.quantity) AS total_quantity
FROM orders o
         JOIN order_items oi ON oi.order_id = o.id
         JOIN payments p ON p.order_id = o.id
WHERE oi.ticket_product_id = @fixture_product_id
GROUP BY o.status, p.status;

SELECT id, name, stock, status
FROM ticket_products
WHERE id = @fixture_product_id;

SELECT COUNT(*) AS total_orders FROM orders;
SELECT COUNT(*) AS total_order_items FROM order_items;
SELECT COUNT(*) AS total_payments FROM payments;