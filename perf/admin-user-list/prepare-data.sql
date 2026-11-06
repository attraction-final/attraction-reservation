-- 개인 attraction_perf DB의 관리자 목록 조회 실험 전용.
-- 기존 회원·주문은 변경하지 않고 합성 회원만 추가한다.
USE attraction_perf;
SET NAMES utf8mb4;

START TRANSACTION;

INSERT INTO users (
    created_at, updated_at, email, password, nickname,
    role, status, provider, provider_user_id, withdrawn_at
)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2
    UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
    UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
    UNION ALL SELECT 9
),
     numbers AS (
         SELECT
             a.n + b.n * 10 + c.n * 100
                 + d.n * 1000 + e.n * 10000 AS n
         FROM digits a
                  CROSS JOIN digits b
                  CROSS JOIN digits c
                  CROSS JOIN digits d
                  CROSS JOIN digits e
     )
SELECT
    DATE_ADD('2026-01-01 00:00:00',
             INTERVAL FLOOR(numbers.n / 2) SECOND),
    DATE_ADD('2026-01-01 00:00:00',
             INTERVAL FLOOR(numbers.n / 2) SECOND),
    CONCAT('perfadminlist', LPAD(numbers.n, 5, '0'), '@example.test'),
    template.password,
    CONCAT('관리자목록테스트', LPAD(numbers.n, 5, '0')),
    'USER',
    CASE
        WHEN MOD(numbers.n, 10) < 7 THEN 'ACTIVE'
        WHEN MOD(numbers.n, 10) = 7 THEN 'INACTIVE'
        WHEN MOD(numbers.n, 10) = 8 THEN 'SUSPENDED'
        ELSE 'WITHDRAWN'
        END,
    'LOCAL',
    NULL,
    CASE
        WHEN MOD(numbers.n, 10) = 9
            THEN '2026-02-01 00:00:00'
        ELSE NULL
        END
FROM numbers
         JOIN users AS template
              ON template.email = 'perf0000@example.test'
WHERE template.provider = 'LOCAL'
  AND template.status = 'ACTIVE'
  AND template.password IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM users AS existing
    WHERE existing.email = CONCAT(
            'perfadminlist', LPAD(numbers.n, 5, '0'), '@example.test'
                           )
);

COMMIT;

SELECT status, COUNT(*) AS fixture_count
FROM users
WHERE email REGEXP '^perfadminlist[0-9]{5}@example[.]test$'
GROUP BY status;

SELECT COUNT(*) AS total_users FROM users;

SELECT COUNT(*) AS list_target_users
FROM users
WHERE status <> 'WITHDRAWN';