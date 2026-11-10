-- KAN-150 전용 DB에서 기존 lock2k-04 회원·슬롯을 읽어 검사 쿼리의 계획을 확인한다.
-- Querydsl 조건을 옮긴 동등 SQL이며 Hibernate가 실제 생성한 SQL을 캡처한 것은 아니다.
-- 데이터·인덱스·통계는 변경하지 않는다. EXPLAIN ANALYZE는 SELECT를 실제 실행한다.
START TRANSACTION READ ONLY;
SET @source_run = 'lock2k-04';
SET @user_id = (
    SELECT MIN(id) FROM users
    WHERE email LIKE CONCAT('kan150-', @source_run, '-%@example.test')
);
SET @slot_id = (
    SELECT MIN(s.id) FROM attraction_slots s
    JOIN attractions a ON a.id = s.attraction_id
    WHERE a.name = CONCAT('KAN150-', @source_run, '-0')
);
SET @starts_at = (SELECT starts_at FROM attraction_slots WHERE id = @slot_id);
SET @ends_at = (SELECT ends_at FROM attraction_slots WHERE id = @slot_id);

SELECT DATABASE() AS database_name, @source_run AS source_run,
       @user_id AS user_id, @slot_id AS slot_id,
       @starts_at AS starts_at, @ends_at AS ends_at,
       (@user_id IS NOT NULL AND @slot_id IS NOT NULL) AS fixture_found;
-- fixture_found=0이면 계획을 유효한 검증 자료로 사용하지 않는다.
SELECT COUNT(*) AS total_reservations FROM reservations;
SELECT reservation_status, COUNT(*) AS member_reservations
FROM reservations WHERE user_id = @user_id GROUP BY reservation_status;
SHOW INDEX FROM reservations;
SHOW INDEX FROM attraction_slots;

SELECT 'duplicate_check_existing_reservation' AS check_name;
EXPLAIN ANALYZE
SELECT 1 FROM reservations r
JOIN attraction_slots s ON s.id = r.attraction_slot_id
WHERE r.user_id = @user_id AND s.id = @slot_id
  AND r.reservation_status = 'RESERVED'
LIMIT 1;

SELECT 'time_conflict_check_existing_reservation' AS check_name;
EXPLAIN ANALYZE
SELECT 1 FROM reservations r
JOIN attraction_slots s ON s.id = r.attraction_slot_id
WHERE r.user_id = @user_id AND r.reservation_status = 'RESERVED'
  AND s.starts_at < @ends_at AND s.ends_at > @starts_at
LIMIT 1;

-- 충돌이 없는 시간대에서는 해당 회원의 후보를 끝까지 검사하는지 비교한다.
-- 시간 경계는 실행계획 진단용이며 예약 생성 API를 호출하거나 우회하지 않는다.
SELECT 'time_conflict_check_no_match' AS check_name;
EXPLAIN ANALYZE
SELECT 1 FROM reservations r
JOIN attraction_slots s ON s.id = r.attraction_slot_id
WHERE r.user_id = @user_id AND r.reservation_status = 'RESERVED'
  AND s.starts_at < DATE_ADD(@ends_at, INTERVAL 10 MINUTE)
  AND s.ends_at > @ends_at
LIMIT 1;
COMMIT;
