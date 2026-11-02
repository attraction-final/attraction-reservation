-- 개인 attraction_perf DB의 내 정보 조회 부하 테스트 전용.
-- 회원가입 기능이나 비밀번호 암호화 성능을 검증하는 데이터가 아니다.
USE attraction_perf;
SET NAMES utf8mb4;

START TRANSACTION;

-- 기존 합성 계정의 테스트용 비밀번호 해시를 재사용한다.
-- 비밀번호 해시 자체를 결과에 출력하지 않는다.
INSERT INTO users (
    created_at,
    updated_at,
    email,
    password,
    nickname,
    role,
    status,
    provider,
    provider_user_id,
    withdrawn_at
)
SELECT
    NOW(6),
    NOW(6),
    CONCAT('perfme', LPAD(seq.n, 4, '0'), '@example.test'),
    template.password,
    CONCAT('부하테스트', LPAD(seq.n, 4, '0')),
    'USER',
    'ACTIVE',
    'LOCAL',
    NULL,
    NULL
FROM (
         SELECT a.n + 10 * b.n + 100 * c.n + 1000 * d.n AS n
         FROM (
                  SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2
                  UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
                  UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
                  UNION ALL SELECT 9
              ) AS a
                  CROSS JOIN (
             SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2
             UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
             UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
             UNION ALL SELECT 9
         ) AS b
                  CROSS JOIN (
             SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2
             UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
             UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
             UNION ALL SELECT 9
         ) AS c
                  CROSS JOIN (
             SELECT 0 AS n UNION ALL SELECT 1
         ) AS d
     ) AS seq
         JOIN users AS template
              ON template.email = 'perf0000@example.test'
WHERE seq.n BETWEEN 100 AND 1999
  AND template.provider = 'LOCAL'
  AND template.status = 'ACTIVE'
  AND template.role = 'USER'
  AND template.password IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
    FROM users AS existing
    WHERE existing.email =
          CONCAT('perfme', LPAD(seq.n, 4, '0'), '@example.test')
);

COMMIT;

SELECT COUNT(*) AS original_test_users
FROM users
WHERE email REGEXP '^perf[0-9]{4}@example[.]test$';

SELECT COUNT(*) AS additional_test_users
FROM users
WHERE email REGEXP '^perfme[0-9]{4}@example[.]test$';

SELECT COUNT(*) AS active_load_test_users
FROM users
WHERE email REGEXP '^(perf[0-9]{4}|perfme[0-9]{4})@example[.]test$'
  AND status = 'ACTIVE'
  AND role = 'USER'
  AND provider = 'LOCAL';

SELECT COUNT(*) AS order_count FROM orders;