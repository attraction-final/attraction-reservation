-- 주문 생성·취소 부하 실험 전용 상품.
USE attraction_order_write_capacity_perf;
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SHARED', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SHARED'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0000', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0000'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0001', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0001'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0002', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0002'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0003', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0003'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0004', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0004'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0005', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0005'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0006', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0006'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0007', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0007'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0008', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0008'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0009', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0009'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0010', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0010'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0011', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0011'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0012', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0012'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0013', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0013'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0014', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0014'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0015', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0015'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0016', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0016'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0017', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0017'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0018', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0018'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0019', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0019'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0020', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0020'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0021', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0021'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0022', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0022'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0023', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0023'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0024', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0024'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0025', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0025'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0026', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0026'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0027', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0027'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0028', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0028'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0029', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0029'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0030', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0030'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0031', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0031'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0032', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0032'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0033', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0033'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0034', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0034'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0035', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0035'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0036', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0036'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0037', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0037'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0038', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0038'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0039', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0039'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0040', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0040'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0041', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0041'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0042', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0042'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0043', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0043'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0044', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0044'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0045', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0045'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0046', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0046'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0047', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0047'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0048', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0048'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0049', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0049'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0050', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0050'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0051', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0051'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0052', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0052'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0053', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0053'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0054', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0054'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0055', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0055'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0056', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0056'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0057', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0057'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0058', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0058'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0059', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0059'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0060', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0060'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0061', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0061'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0062', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0062'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0063', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0063'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0064', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0064'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0065', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0065'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0066', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0066'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0067', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0067'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0068', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0068'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0069', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0069'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0070', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0070'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0071', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0071'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0072', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0072'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0073', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0073'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0074', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0074'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0075', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0075'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0076', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0076'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0077', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0077'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0078', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0078'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0079', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0079'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0080', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0080'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0081', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0081'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0082', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0082'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0083', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0083'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0084', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0084'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0085', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0085'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0086', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0086'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0087', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0087'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0088', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0088'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0089', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0089'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0090', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0090'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0091', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0091'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0092', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0092'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0093', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0093'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0094', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0094'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0095', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0095'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0096', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0096'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0097', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0097'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0098', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0098'
);


INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
    'PERF_WRITE_CAPACITY_SPLIT_0099', '주문 부하 실험 전용 상품',
    10000, 10000, 'ON_SALE', 'ALL_DAY', 0
WHERE NOT EXISTS (
    SELECT 1 FROM ticket_products WHERE name = 'PERF_WRITE_CAPACITY_SPLIT_0099'
);

COMMIT;

SELECT
    CASE
        WHEN name = 'PERF_WRITE_CAPACITY_SHARED'
        THEN 'SHARED'
        ELSE 'SPLIT'
    END AS product_group,
    COUNT(*) AS product_count,
    MIN(price) AS min_price,
    MAX(price) AS max_price,
    MIN(stock) AS min_stock,
    MAX(stock) AS max_stock,
    SUM(status = 'ON_SALE') AS on_sale_count
FROM ticket_products
WHERE name = 'PERF_WRITE_CAPACITY_SHARED'
   OR name REGEXP '^PERF_WRITE_CAPACITY_SPLIT_[0-9]{4}$'
GROUP BY product_group;

SELECT COUNT(*) AS existing_orders FROM orders;
