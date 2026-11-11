-- 주문 집중 요청 실험의 기본 검사 전용 상품.
USE attraction_order_create_burst_perf;
SET NAMES utf8mb4;

INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_ORDER_CREATE_BURST_SMOKE',
    '주문 생성·취소 기본 검사 전용',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products
    WHERE name = 'PERF_ORDER_CREATE_BURST_SMOKE'
);

SELECT id, name, price, stock, status
FROM ticket_products
WHERE name = 'PERF_ORDER_CREATE_BURST_SMOKE';
