-- 개인 attraction_perf DB의 주문 생성·취소 검사 전용.
USE attraction_perf;
SET NAMES utf8mb4;

START TRANSACTION;

-- 기존 테스트 비밀번호 해시를 사용하되 별도 회원을 만든다.
INSERT INTO users (
    created_at, updated_at, email, password, nickname,
    role, status, provider, provider_user_id, withdrawn_at
)
SELECT
    NOW(6), NOW(6),
    CONCAT('perfwrite', LPAD(seq.n, 4, '0'), '@example.test'),
    template.password,
    CONCAT('주문쓰기테스트', LPAD(seq.n, 4, '0')),
    'USER', 'ACTIVE', 'LOCAL', NULL, NULL
FROM (
         SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2
         UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
         UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
         UNION ALL SELECT 9
     ) AS seq
         JOIN users AS template
              ON template.email = 'perf0000@example.test'
WHERE template.provider = 'LOCAL'
  AND template.status = 'ACTIVE'
  AND template.password IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM users AS existing
    WHERE existing.email = CONCAT(
            'perfwrite', LPAD(seq.n, 4, '0'), '@example.test'
                           )
);

-- 기존 상품과 구분되는 소규모 검사 전용 상품.
INSERT INTO ticket_products (
    created_at, updated_at, name, description,
    price, stock, status, type, version
)
SELECT
    NOW(6), NOW(6),
    'PERF_ORDER_WRITE_SMOKE',
    '개인 주문 생성·취소 검사 전용',
    10000, 100, 'ON_SALE', 'ALL_DAY', 0
    WHERE NOT EXISTS (
    SELECT 1
    FROM ticket_products
    WHERE name = 'PERF_ORDER_WRITE_SMOKE'
);

COMMIT;

SELECT COUNT(*) AS write_test_users
FROM users
WHERE email REGEXP '^perfwrite[0-9]{4}@example[.]test$'
  AND role = 'USER'
  AND status = 'ACTIVE'
  AND provider = 'LOCAL';

SELECT id, name, price, stock, status, version
FROM ticket_products
WHERE name = 'PERF_ORDER_WRITE_SMOKE';

SELECT COUNT(*) AS order_count FROM orders;
SELECT COUNT(*) AS payment_count FROM payments;