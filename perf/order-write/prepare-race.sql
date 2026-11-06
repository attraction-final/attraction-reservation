-- 개인 테스트 DB의 동시 주문 정확성 검사 전용.
USE attraction_perf;
SET NAMES utf8mb4;

INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    NOW(6), NOW(6),
    'PERF_ORDER_WRITE_RACE_01',
    '재고 5개에 회원 10명이 주문하는 동시 요청 검사',
    10000, 5, 'ON_SALE', 'ALL_DAY', 0
    WHERE NOT EXISTS (
    SELECT 1
    FROM ticket_products
    WHERE name = 'PERF_ORDER_WRITE_RACE_01'
);

SELECT id, name, price, stock, status, version
FROM ticket_products
WHERE name = 'PERF_ORDER_WRITE_RACE_01';

SELECT COUNT(*) AS existing_race_order_items
FROM order_items oi
         JOIN ticket_products tp ON tp.id = oi.ticket_product_id
WHERE tp.name = 'PERF_ORDER_WRITE_RACE_01';

SELECT COUNT(*) AS active_write_test_users
FROM users
WHERE email REGEXP '^perfwrite[0-9]{4}@example[.]test$'
  AND role = 'USER'
  AND status = 'ACTIVE'
  AND provider = 'LOCAL';