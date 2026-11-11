-- KAN-160: 홈페이지 판매 목록에 필요한 종일권·오후권 초기 상품 등록
-- 동일 종류의 비삭제 상품이 있으면 추가하지 않는다.
-- 기존 상품의 가격·재고·상태는 변경하지 않는다.

INSERT INTO ticket_products (
    name,
    price,
    description,
    type,
    stock,
    status,
    version,
    created_at,
    updated_at
)
SELECT
    '종일권',
    50000,
    '개장부터 폐장까지 이용할 수 있어요',
    'ALL_DAY',
    1000,
    'ON_SALE',
    0,
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM ticket_products
    WHERE type = 'ALL_DAY'
      AND status <> 'DELETED'
);

INSERT INTO ticket_products (
    name,
    price,
    description,
    type,
    stock,
    status,
    version,
    created_at,
    updated_at
)
SELECT
    '오후권',
    35000,
    '오후 3시부터 폐장까지 이용할 수 있어요',
    'AFTERNOON',
    1000,
    'ON_SALE',
    0,
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM ticket_products
    WHERE type = 'AFTERNOON'
      AND status <> 'DELETED'
);
