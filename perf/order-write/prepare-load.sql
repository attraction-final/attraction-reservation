-- 개인 테스트 DB의 주문 생성·취소 반복 측정 전용.
USE attraction_perf;
SET NAMES utf8mb4;

INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    NOW(6), NOW(6),
    'PERF_ORDER_WRITE_LOAD',
    '같은 상품에 대한 주문 생성·취소 반복 측정 전용',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
    WHERE NOT EXISTS (
    SELECT 1
    FROM ticket_products
    WHERE name = 'PERF_ORDER_WRITE_LOAD'
);

SELECT id, name, price, stock, status, version
FROM ticket_products
WHERE name = 'PERF_ORDER_WRITE_LOAD';

SELECT COUNT(DISTINCT oi.order_id) AS existing_load_orders
FROM order_items oi
         JOIN ticket_products tp ON tp.id = oi.ticket_product_id
WHERE tp.name = 'PERF_ORDER_WRITE_LOAD';

SELECT COUNT(*) AS active_write_test_users
FROM users
WHERE email REGEXP '^perfwrite[0-9]{4}@example[.]test$'
  AND role = 'USER'
  AND status = 'ACTIVE'
  AND provider = 'LOCAL';