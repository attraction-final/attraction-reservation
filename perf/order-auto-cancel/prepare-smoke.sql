-- 자동 취소 정확성 검사용 합성 데이터.
-- 기존 실험 DB는 조회만 하고, 새 전용 DB에만 데이터를 추가한다.
USE attraction_auto_cancel_perf;
SET time_zone = '+00:00';

START TRANSACTION;

SET @fixture_now = UTC_TIMESTAMP(6);
SET @visit_date = DATE_ADD(
    DATE(CONVERT_TZ(@fixture_now, '+00:00', '+09:00')),
    INTERVAL 1 DAY
);

-- 기존 테스트 회원의 로그인 정보를 전용 회원으로 복사한다.
-- 비밀번호와 토큰은 출력하지 않는다.
INSERT INTO users (
    created_at, updated_at,
    email, nickname, password,
    provider, provider_user_id,
    role, status, withdrawn_at
)
SELECT
    @fixture_now, @fixture_now,
    'perfautocancel0000@example.test',
    '자동취소검사회원',
    password,
    'LOCAL', NULL,
    'USER', 'ACTIVE', NULL
FROM attraction_perf.users
WHERE email = 'perf0000@example.test'
  AND provider = 'LOCAL'
  AND status = 'ACTIVE'
  AND password IS NOT NULL;

SET @fixture_user_id = LAST_INSERT_ID();

INSERT INTO ticket_products (
    created_at, updated_at,
    name, description,
    price, stock, status, type, version
)
VALUES (
           @fixture_now, @fixture_now,
           'PERF_AUTO_CANCEL_SMOKE_01',
           '자동 취소 정확성 검사용 합성 상품',
           10000, 95, 'ON_SALE', 'ALL_DAY', 0
       );

SET @fixture_product_id = LAST_INSERT_ID();

-- 임시 표로 주문별 조건을 정의한다.
CREATE TEMPORARY TABLE auto_cancel_cases (
    label VARCHAR(24) PRIMARY KEY,
    age_minutes INT NOT NULL,
    order_status VARCHAR(24) NOT NULL,
    payment_status VARCHAR(24) NOT NULL
);

INSERT INTO auto_cancel_cases VALUES
                                  ('expired-60', 60, 'PENDING',   'PENDING'),
                                  ('expired-45', 45, 'PENDING',   'PENDING'),
                                  ('expired-35', 35, 'PENDING',   'PENDING'),
                                  ('fresh',       0, 'PENDING',   'PENDING'),
                                  ('paid',       90, 'COMPLETED', 'PAID'),
                                  ('cancelled', 120, 'CANCELLED', 'CANCELLED');

INSERT INTO orders (
    created_at, updated_at,
    status, total_amount, visit_date, user_id
)
SELECT
    DATE_SUB(@fixture_now, INTERVAL c.age_minutes MINUTE),
    @fixture_now,
    c.order_status,
    10000,
    @visit_date,
    @fixture_user_id
FROM auto_cancel_cases c
ORDER BY c.age_minutes DESC;

INSERT INTO order_items (
    price, quantity, order_id, ticket_product_id
)
SELECT
    10000, 1, o.id, @fixture_product_id
FROM orders o
WHERE o.user_id = @fixture_user_id;

INSERT INTO payments (
    created_at, updated_at,
    amount, paid_at, pg_id, status, order_id
)
SELECT
    o.created_at,
    @fixture_now,
    10000,
    CASE
        WHEN c.payment_status = 'PAID'
            THEN DATE_ADD(o.created_at, INTERVAL 1 MINUTE)
        ELSE NULL
        END,
    CONCAT('perf-auto-smoke-01-', c.label),
    c.payment_status,
    o.id
FROM orders o
         JOIN auto_cancel_cases c
              ON o.created_at =
                 DATE_SUB(@fixture_now, INTERVAL c.age_minutes MINUTE)
WHERE o.user_id = @fixture_user_id;

COMMIT;

SELECT
    o.id AS order_id,
    p.pg_id AS test_case,
    o.status AS order_status,
    p.status AS payment_status,
    TIMESTAMPDIFF(
        MINUTE, o.created_at, UTC_TIMESTAMP(6)
    ) AS age_minutes
FROM orders o
         JOIN payments p ON p.order_id = o.id
WHERE o.user_id = @fixture_user_id
ORDER BY o.created_at, o.id;

SELECT id, name, stock, status
FROM ticket_products
WHERE id = @fixture_product_id;

SELECT
    (SELECT COUNT(*) FROM orders) AS order_count,
    (SELECT COUNT(*) FROM order_items) AS item_count,
    (SELECT COUNT(*) FROM payments) AS payment_count;