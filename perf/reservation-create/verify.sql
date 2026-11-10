-- @run_id는 run.sh가 검증한 실행 식별자다. 해당 실행의 슬롯과 예약만 읽는다.
CREATE TEMPORARY TABLE guard (ok TINYINT NOT NULL CHECK (ok = 1));
INSERT INTO guard SELECT IF(DATABASE()='reservation_create_perf',1,0);
SET @slot_id=(SELECT s.id FROM attraction_slots s JOIN attractions a ON a.id=s.attraction_id WHERE a.name=CONCAT('KAN150-',@run_id));
SELECT JSON_OBJECT('slotId',s.id,'capacity',s.capacity,'reservedCount',s.reserved_count,
  'status',s.status,'autoClosed',s.auto_closed,
  'reservations',(SELECT COUNT(*) FROM reservations r WHERE r.attraction_slot_id=s.id),
  'reserved',(SELECT COUNT(*) FROM reservations r WHERE r.attraction_slot_id=s.id AND r.reservation_status='RESERVED'))
FROM attraction_slots s WHERE s.id=@slot_id;
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM attraction_slots WHERE id=@slot_id AND capacity=20
    AND reserved_count=20 AND status='CLOSED' AND auto_closed=1)=1,1,0);
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM reservations WHERE attraction_slot_id=@slot_id)=20
    AND (SELECT COUNT(DISTINCT user_id) FROM reservations WHERE attraction_slot_id=@slot_id)=20
    AND (SELECT COUNT(*) FROM reservations WHERE attraction_slot_id=@slot_id AND reservation_status='RESERVED')=20,1,0);
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM reservations r
    JOIN tickets t ON t.id=r.ticket_id JOIN order_items oi ON oi.id=t.order_item_id
    JOIN orders o ON o.id=oi.order_id JOIN users u ON u.id=r.user_id
    WHERE r.attraction_slot_id=@slot_id AND o.user_id=r.user_id AND t.status='IN_USE'
      AND t.valid_date=DATE(UTC_TIMESTAMP()+INTERVAL 9 HOUR)
      AND u.email LIKE CONCAT('kan150-',@run_id,'-%@example.test'))=20,1,0);
-- 이번 실행 회원에게 다른 슬롯의 예약이나 실패 후 남은 추가 예약이 없어야 한다.
INSERT INTO guard SELECT IF((SELECT COUNT(*) FROM reservations r JOIN users u ON u.id=r.user_id
    WHERE u.email LIKE CONCAT('kan150-',@run_id,'-%@example.test'))=20,1,0);
SELECT 'DB verification passed: capacity=reservedCount=activeReservations=20, distinctUsers=20';
