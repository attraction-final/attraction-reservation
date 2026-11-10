-- @run_id는 run.sh가 검증한 실행 식별자다. 해당 실행의 슬롯과 예약만 읽는다.
CREATE TEMPORARY TABLE guard (ok TINYINT NOT NULL CHECK (ok = 1));
INSERT INTO guard SELECT IF(DATABASE()='reservation_create_perf',1,0);
INSERT INTO guard SELECT IF(@scenario IN ('sold-out','single','spread'),1,0);
INSERT INTO guard SELECT IF(@users IN (50,100,200,500,1000),1,0);
SET @groups=IF(@scenario='spread',5,1);
SET @capacity=IF(@scenario='sold-out',20,@users DIV @groups);
SET @expected=IF(@scenario='sold-out',20,@users);
CREATE TEMPORARY TABLE run_slots AS
SELECT s.id AS slot_id FROM attraction_slots s JOIN attractions a ON a.id=s.attraction_id
WHERE LEFT(a.name,CHAR_LENGTH(CONCAT('KAN150-',@run_id,'-')))=CONCAT('KAN150-',@run_id,'-');
SELECT JSON_OBJECT('slotId',s.id,'capacity',s.capacity,'reservedCount',s.reserved_count,
  'status',s.status,'autoClosed',s.auto_closed,
  'reservations',(SELECT COUNT(*) FROM reservations r WHERE r.attraction_slot_id=s.id),
  'reserved',(SELECT COUNT(*) FROM reservations r WHERE r.attraction_slot_id=s.id AND r.reservation_status='RESERVED'))
FROM run_slots rs JOIN attraction_slots s ON s.id=rs.slot_id ORDER BY s.id;
-- 각 슬롯의 실제 예약 수와 카운트를 함께 확인한다. 임시 테이블은 각 쿼리에서 한 번만 읽는다.
INSERT INTO guard SELECT IF(COUNT(*)=@groups AND SUM(
    s.capacity=@capacity AND s.reserved_count=@capacity AND s.status='CLOSED' AND s.auto_closed=1
    AND COALESCE(r.total,0)=@capacity AND COALESCE(r.active,0)=@capacity)=@groups,1,0)
FROM run_slots rs JOIN attraction_slots s ON s.id=rs.slot_id
LEFT JOIN (SELECT attraction_slot_id,COUNT(*) AS total,SUM(reservation_status='RESERVED') AS active
    FROM reservations GROUP BY attraction_slot_id) r ON r.attraction_slot_id=s.id;
INSERT INTO guard SELECT IF(COUNT(*)=@expected AND COUNT(DISTINCT r.user_id)=@expected
    AND SUM(r.reservation_status='RESERVED')=@expected,1,0)
FROM run_slots rs JOIN reservations r ON r.attraction_slot_id=rs.slot_id;
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM reservations r
    JOIN run_slots rs ON rs.slot_id=r.attraction_slot_id
    JOIN tickets t ON t.id=r.ticket_id JOIN order_items oi ON oi.id=t.order_item_id
    JOIN orders o ON o.id=oi.order_id JOIN users u ON u.id=r.user_id
    WHERE o.user_id=r.user_id AND t.status='IN_USE'
      AND t.valid_date=DATE(UTC_TIMESTAMP()+INTERVAL 9 HOUR)
      AND u.email LIKE CONCAT('kan150-',@run_id,'-%@example.test'))=@expected,1,0);
-- 이번 실행 회원에게 다른 슬롯의 예약이나 실패 후 남은 추가 예약이 없어야 한다.
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM reservations r JOIN users u ON u.id=r.user_id
    WHERE u.email LIKE CONCAT('kan150-',@run_id,'-%@example.test'))=@expected,1,0);
SELECT CONCAT('DB verification passed: scenario=',@scenario,', slots=',@groups,
    ', capacityPerSlot=',@capacity,', totalReservations=distinctUsers=',@expected);
