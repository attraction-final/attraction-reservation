-- 통합 부하 실험 전용 상품. 기존 상품의 재고는 변경하지 않는다.
USE attraction_service_capacity_perf;
SET NAMES utf8mb4;

INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_SERVICE_HISTORY',
    '통합 실험 조회용 주문의 상품',
    10000, 1000000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products
    WHERE name = 'PERF_SERVICE_HISTORY'
);

INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_SERVICE_WRITE',
    '통합 실험 주문 생성 및 취소용 공통 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products
    WHERE name = 'PERF_SERVICE_WRITE'
);

SELECT DATABASE() AS target_database;

SELECT id, name, price, stock, status
FROM ticket_products
WHERE name IN ('PERF_SERVICE_HISTORY', 'PERF_SERVICE_WRITE')
ORDER BY name;
