-- run.sh가 검증한 @run_id를 앞에서 주입한다. KAN-150 전용 DB에서 새 실행 데이터만 추가한다.
CREATE TEMPORARY TABLE guard (ok TINYINT NOT NULL CHECK (ok = 1));
INSERT INTO guard SELECT IF(DATABASE() = 'reservation_create_perf',1,0);
SET @kst = UTC_TIMESTAMP() + INTERVAL 9 HOUR;
SET @today = DATE(@kst);
INSERT INTO guard SELECT IF(@scenario IN ('sold-out','single','spread'),1,0);
SET @groups=IF(@scenario='spread',5,1);
SET @capacity=IF(@scenario='sold-out',20,IF(@scenario='single',50,10));
-- 오늘 21:50~22:00 슬롯. 마감 21:20보다 최소 10분 앞서 준비를 마쳐야 한다.
INSERT INTO guard SELECT IF(TIME(@kst) >= '10:00:00' AND TIME(@kst) < '21:10:00',1,0);
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM attractions
    WHERE name=CONCAT('KAN150-',@run_id) OR LEFT(name,CHAR_LENGTH(CONCAT('KAN150-',@run_id,'-')))=CONCAT('KAN150-',@run_id,'-'))=0,1,0);
CREATE TEMPORARY TABLE run_users AS
SELECT id AS user_id,email,CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(email,'@',1),'-',-1) AS UNSIGNED) AS n
FROM users WHERE email LIKE CONCAT('kan150-',@run_id,'-%@example.test')
  AND status='ACTIVE' AND role='USER' AND provider='LOCAL';
-- MySQL 임시 테이블은 한 쿼리에서 여러 번 열 수 없어 단일 집계로 확인한다.
INSERT INTO guard SELECT IF(COUNT(*)=50 AND COUNT(DISTINCT n)=50
    AND MIN(n)=0 AND MAX(n)=49,1,0) FROM run_users;
CREATE TEMPORARY TABLE run_layout(n INT PRIMARY KEY);
INSERT INTO run_layout SELECT n FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) ns WHERE n < @groups;

START TRANSACTION;
INSERT INTO ticket_products(created_at,updated_at,name,description,price,stock,status,type,version)
VALUES(UTC_TIMESTAMP(),UTC_TIMESTAMP(),CONCAT('KAN150-',@run_id),'create fixture',10000,1000,'ON_SALE','ALL_DAY',0);
SET @product_id=LAST_INSERT_ID();
INSERT INTO attractions(created_at,updated_at,name,description,duration_minutes,entrance_move_minutes,location,operation_status)
SELECT UTC_TIMESTAMP(),UTC_TIMESTAMP(),CONCAT('KAN150-',@run_id,'-',n),'create fixture',10,5,'A','OPERATING' FROM run_layout;
INSERT INTO attraction_slots(created_at,updated_at,auto_closed,capacity,reserved_count,starts_at,ends_at,status,attraction_id)
SELECT UTC_TIMESTAMP(),UTC_TIMESTAMP(),0,@capacity,0,TIMESTAMP(@today,'21:50:00'),TIMESTAMP(@today,'22:00:00'),'OPEN',a.id
FROM run_layout l JOIN attractions a ON a.name=CONCAT('KAN150-',@run_id,'-',l.n);
CREATE TEMPORARY TABLE run_slots AS
SELECT l.n,s.id AS slot_id FROM run_layout l
JOIN attractions a ON a.name=CONCAT('KAN150-',@run_id,'-',l.n)
JOIN attraction_slots s ON s.attraction_id=a.id;
INSERT INTO guard SELECT IF(COUNT(*)=@groups,1,0) FROM run_slots;
INSERT INTO orders(created_at,updated_at,status,total_amount,visit_date,user_id)
SELECT UTC_TIMESTAMP(),UTC_TIMESTAMP(),'COMPLETED',10000,@today,user_id FROM run_users;
INSERT INTO order_items(price,quantity,order_id,ticket_product_id)
SELECT 10000,1,o.id,@product_id FROM orders o JOIN run_users u ON u.user_id=o.user_id;
INSERT INTO tickets(created_at,updated_at,code,entered_at,name,status,type,valid_date,order_item_id)
SELECT UTC_TIMESTAMP(),UTC_TIMESTAMP(),CONCAT('KAN150-',@run_id,'-',u.n),
       TIMESTAMP(@today,'01:00:00'),CONCAT('KAN150-',@run_id),'IN_USE','ALL_DAY',@today,oi.id
FROM run_users u JOIN orders o ON o.user_id=u.user_id
JOIN order_items oi ON oi.order_id=o.id AND oi.ticket_product_id=@product_id;
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM tickets t JOIN order_items oi ON oi.id=t.order_item_id
    WHERE oi.ticket_product_id=@product_id)=50,1,0);
COMMIT;
SELECT JSON_OBJECT('email',u.email,'userId',u.user_id,'ticketId',t.id,'slotId',s.slot_id,'scenario',@scenario,
                   'date',CAST(@today AS CHAR),'startsAt',CAST(TIMESTAMP(@today,'21:50:00') AS CHAR))
FROM run_users u JOIN orders o ON o.user_id=u.user_id
JOIN order_items oi ON oi.order_id=o.id AND oi.ticket_product_id=@product_id
JOIN tickets t ON t.order_item_id=oi.id
JOIN run_slots s ON s.n=IF(@scenario='spread',FLOOR(u.n/10),0) ORDER BY u.email;
