-- KAN-134 전용 reservation_perf DB용. 운영/공유 DB의 마이그레이션이 아니다.
-- 기존 Node.js fixture와 동일한 200명 × 500건이다. 사용자 비밀번호는 실제 가입 API가 만든다.
-- 기존 데이터는 삭제하거나 덮어쓰지 않는다. 조건이 맞지 않으면 CHECK 위반으로 중단한다.
CREATE TEMPORARY TABLE perf_guard (
    ok TINYINT NOT NULL CHECK (ok = 1)
);
INSERT INTO perf_guard SELECT IF(DATABASE() = 'reservation_perf', 1, 0);

CREATE TEMPORARY TABLE perf_numbers (n INT NOT NULL PRIMARY KEY);
INSERT INTO perf_numbers
SELECT ones.n + tens.n * 10 + hundreds.n * 100
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) hundreds;

CREATE TEMPORARY TABLE perf_users AS
SELECT n.n AS account_index, u.id AS user_id
FROM perf_numbers n JOIN users u ON u.email = CONCAT('kan134-', LPAD(n.n, 4, '0'), '@example.test')
WHERE n.n < 200 AND u.status = 'ACTIVE' AND u.role = 'USER' AND u.provider = 'LOCAL';
INSERT INTO perf_guard SELECT IF((SELECT COUNT(*) FROM perf_users) = 200
    AND (SELECT COUNT(*) FROM users) = 200, 1, 0);

SET @seed = ((SELECT COUNT(*) FROM reservations) = 0);
-- 빈 fixture라면 회원 외 테이블도 비어 있어야 한다. 일부만 생성된 데이터는 보수적으로 거절한다.
INSERT INTO perf_guard SELECT IF(NOT @seed OR (
    (SELECT COUNT(*) FROM ticket_products) = 0 AND (SELECT COUNT(*) FROM orders) = 0
    AND (SELECT COUNT(*) FROM order_items) = 0 AND (SELECT COUNT(*) FROM tickets) = 0
    AND (SELECT COUNT(*) FROM attractions) = 0 AND (SELECT COUNT(*) FROM attraction_slots) = 0), 1, 0);

START TRANSACTION;
INSERT INTO ticket_products (id,created_at,updated_at,description,name,price,status,stock,type,version)
SELECT 1,'2026-09-01 01:00:00','2026-09-01 01:00:00','read-only fixture','KAN134 day ticket',
       10000,'ON_SALE',1000000,'ALL_DAY',0 WHERE @seed;

INSERT INTO orders (id,created_at,updated_at,status,total_amount,visit_date,user_id)
SELECT u.account_index * 10 + d.n + 1,'2026-09-01 01:00:00','2026-09-01 01:00:00',
       'COMPLETED',10000,DATE_ADD('2026-10-01', INTERVAL d.n DAY),u.user_id
FROM perf_users u CROSS JOIN perf_numbers d WHERE d.n < 10 AND @seed;

INSERT INTO order_items (id,price,quantity,order_id,ticket_product_id)
SELECT n + 1,10000,1,n + 1,1 FROM (
    SELECT u.account_index * 10 + d.n AS n FROM perf_users u CROSS JOIN perf_numbers d WHERE d.n < 10
) ids WHERE @seed;

INSERT INTO tickets (id,created_at,updated_at,code,entered_at,name,status,type,valid_date,order_item_id)
SELECT u.account_index * 10 + d.n + 1,'2026-09-01 01:00:00','2026-09-01 01:00:00',
       CONCAT('KAN134-T-',u.account_index * 10 + d.n + 1),
       TIMESTAMP(DATE_ADD('2026-10-01', INTERVAL d.n DAY),'01:00:00'),
       'KAN134 day ticket','IN_USE','ALL_DAY',DATE_ADD('2026-10-01', INTERVAL d.n DAY),
       u.account_index * 10 + d.n + 1
FROM perf_users u CROSS JOIN perf_numbers d WHERE d.n < 10 AND @seed;

INSERT INTO attractions (id,created_at,updated_at,description,duration_minutes,entrance_move_minutes,location,name,operation_status)
SELECT n + 1,'2026-09-01 01:00:00','2026-09-01 01:00:00','read-only fixture',
       10,5,'A',CONCAT('KAN134 attraction ', n + 1),'OPERATING'
FROM perf_numbers WHERE n < 10 AND @seed;

INSERT INTO attraction_slots (id,created_at,updated_at,auto_closed,capacity,ends_at,reserved_count,starts_at,status,attraction_id)
SELECT n + 1,'2026-09-01 01:00:00','2026-09-01 01:00:00',0,200,
       TIMESTAMPADD(MINUTE,MOD(n,50) * 10 + 10,TIMESTAMPADD(DAY,FLOOR(n/50),'2026-10-01 10:00:00')),
       150,TIMESTAMPADD(MINUTE,MOD(n,50) * 10,TIMESTAMPADD(DAY,FLOOR(n/50),'2026-10-01 10:00:00')),
       'OPEN',MOD(n,10) + 1 FROM perf_numbers WHERE @seed;

-- Generated Column active_reservation에는 직접 쓰지 않는다.
INSERT INTO reservations (id,created_at,updated_at,cancel_reason,cancelled_at,reservation_number,
                          reservation_status,attraction_slot_id,ticket_id,user_id)
SELECT n.n * 200 + u.account_index + 1,'2026-09-01 01:00:00','2026-09-01 01:00:00',
       IF(MOD(u.account_index+n.n,4)=2,'USER_REQUEST',NULL),
       IF(MOD(u.account_index+n.n,4)=2,'2026-09-01 01:00:00',NULL),
       CONCAT('KAN134-R-',n.n * 200 + u.account_index + 1),
       ELT(MOD(u.account_index+n.n,4)+1,'RESERVED','COMPLETED','CANCELLED','NO_SHOW'),
       n.n + 1,u.account_index * 10 + FLOOR(n.n/50) + 1,u.user_id
FROM perf_numbers n CROSS JOIN perf_users u WHERE @seed;

-- 새 데이터와 재사용 데이터에 같은 검증을 적용한다. 실패하면 새 INSERT도 롤백된다.
INSERT INTO perf_guard SELECT IF(
    (SELECT COUNT(*) FROM ticket_products) = 1 AND (SELECT COUNT(*) FROM orders) = 2000
    AND (SELECT COUNT(*) FROM order_items) = 2000 AND (SELECT COUNT(*) FROM tickets) = 2000
    AND (SELECT COUNT(*) FROM attractions) = 10 AND (SELECT COUNT(*) FROM attraction_slots) = 500
    AND (SELECT COUNT(*) FROM reservations) = 100000, 1, 0);
INSERT INTO perf_guard SELECT IF((
    SELECT COUNT(*) FROM reservations r
    JOIN perf_users u ON u.user_id = r.user_id
    JOIN perf_numbers n ON n.n + 1 = r.attraction_slot_id
    JOIN tickets t ON t.id = r.ticket_id
    JOIN order_items oi ON oi.id = t.order_item_id
    JOIN orders o ON o.id = oi.order_id
    WHERE r.id = n.n * 200 + u.account_index + 1
      AND r.reservation_number = CONCAT('KAN134-R-',r.id)
      AND r.reservation_status = ELT(MOD(u.account_index+n.n,4)+1,'RESERVED','COMPLETED','CANCELLED','NO_SHOW')
      AND r.ticket_id = u.account_index * 10 + FLOOR(n.n/50) + 1
      AND o.user_id = u.user_id
) = 100000, 1, 0);
INSERT INTO perf_guard SELECT IF((
    SELECT COUNT(*) FROM attraction_slots s JOIN perf_numbers n ON s.id = n.n + 1
    JOIN attractions a ON a.id = s.attraction_id
    WHERE s.starts_at = TIMESTAMPADD(MINUTE,MOD(n.n,50)*10,TIMESTAMPADD(DAY,FLOOR(n.n/50),'2026-10-01 10:00:00'))
      AND s.ends_at = TIMESTAMPADD(MINUTE,10,s.starts_at)
      AND s.attraction_id = MOD(n.n,10)+1 AND a.name = CONCAT('KAN134 attraction ',a.id)
      AND s.capacity = 200 AND s.reserved_count = 150 AND s.status = 'OPEN' AND s.auto_closed = 0
) = 500, 1, 0);
COMMIT;
SELECT 'fixture verified: users=200 reservations=100000';
