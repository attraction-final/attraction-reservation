-- 개인 테스트 DB의 상세 조회용 합성 데이터.
-- 주문 생성·재고·결제 업무 검증에는 사용하지 않는다.
USE attraction_perf;

START TRANSACTION;

INSERT INTO ticket_products
(created_at, updated_at, description, name, price, status, stock, type)
SELECT
    NOW(6), NOW(6), 'Order detail performance fixture',
    seed.name, seed.price, 'ON_SALE', 100000, 'ALL_DAY'
FROM (
         SELECT 'PERF_DETAIL_10000' AS name, 10000 AS price
         UNION ALL SELECT 'PERF_DETAIL_2000', 2000
         UNION ALL SELECT 'PERF_DETAIL_3000', 3000
         UNION ALL SELECT 'PERF_DETAIL_5000', 5000
     ) AS seed
WHERE NOT EXISTS (
    SELECT 1
    FROM ticket_products AS existing
    WHERE existing.name = seed.name
);

-- 회원별 서로 다른 주문 2건을 선택한다.
CREATE TEMPORARY TABLE perf_detail_targets AS
SELECT
    u.id AS user_id,
    MIN(o.id) AS single_order_id,
    MAX(o.id) AS multi_order_id
FROM users AS u
         JOIN orders AS o ON o.user_id = u.id
WHERE u.email REGEXP '^perf[0-9]{4}@example[.]test$'
GROUP BY u.id
HAVING COUNT(*) >= 2;

-- 임시 테이블은 이 쿼리에서 한 번만 참조한다.
INSERT INTO order_items (price, quantity, order_id, ticket_product_id)
SELECT
    seed.price,
    1,
    o.id,
    p.id
FROM perf_detail_targets AS t
         CROSS JOIN (
    SELECT 1 AS is_single, 'PERF_DETAIL_10000' AS name, 10000 AS price
    UNION ALL SELECT 0, 'PERF_DETAIL_2000', 2000
    UNION ALL SELECT 0, 'PERF_DETAIL_3000', 3000
    UNION ALL SELECT 0, 'PERF_DETAIL_5000', 5000
) AS seed
         JOIN orders AS o
              ON o.id = CASE
                            WHEN seed.is_single = 1 THEN t.single_order_id
                            ELSE t.multi_order_id
                  END
         JOIN ticket_products AS p ON p.name = seed.name
WHERE o.total_amount = 10000
  AND o.status = 'COMPLETED'
  AND NOT EXISTS (
    SELECT 1
    FROM order_items AS existing
    WHERE existing.order_id = o.id
);

COMMIT;

SELECT COUNT(*) AS target_user_count
FROM perf_detail_targets;

SELECT COUNT(*) AS detail_order_count
FROM perf_detail_targets AS t
         JOIN orders AS o
              ON o.id = t.single_order_id OR o.id = t.multi_order_id
WHERE EXISTS (
    SELECT 1
    FROM order_items AS oi
    WHERE oi.order_id = o.id
);

SELECT COUNT(*) AS order_item_count FROM order_items;

-- 결과가 0행이면 대상 주문의 금액과 항목 합계가 모두 일치한다.
SELECT
    o.id AS order_id,
    o.total_amount,
    COALESCE(SUM(oi.price * oi.quantity), 0) AS item_total
FROM perf_detail_targets AS t
         JOIN orders AS o
              ON o.id = t.single_order_id OR o.id = t.multi_order_id
         LEFT JOIN order_items AS oi ON oi.order_id = o.id
GROUP BY o.id, o.total_amount
HAVING o.total_amount <> COALESCE(SUM(oi.price * oi.quantity), 0);

DROP TEMPORARY TABLE perf_detail_targets;