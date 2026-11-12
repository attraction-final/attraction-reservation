-- KAN-87 동일 회원·슬롯의 활성 예약 중복 방지
-- reservations 테이블이 존재하고, 보조 컬럼과 제약이 없는 스키마에 한 번 적용한다.
-- 적용 전 예약 쓰기 작업을 중지하고 reservations 테이블을 백업한다.
-- 기존 RESERVED 중복 데이터가 있으면 먼저 처리 방안을 결정한다.
-- MySQL DDL은 일반 transaction rollback으로 되돌릴 수 없다.

-- 파일 전체를 한 번에 실행하지 않고, 단계별 결과를 확인한 뒤 다음 단계로 진행한다.
-- 아래 SELECT는 중복이 있어도 이후 SQL 실행을 자동으로 중단하지 않는다.

-- 1. 적용할 DB와 기존 테이블 구조를 확인한다.
-- reservations 테이블이 없는 빈 DB나 이미 변경이 적용된 DB에는 실행하지 않는다.
SELECT DATABASE() AS target_database;
SHOW CREATE TABLE reservations;

-- 2. 같은 회원·슬롯의 RESERVED 중복을 확인한다.
-- 결과가 0건일 때만 3단계로 진행한다.
-- 결과가 있으면 어떤 예약을 유지하고 정원을 어떻게 맞출지 결정한 뒤 다시 확인한다.
-- 이 스크립트는 기존 예약을 삭제하거나 상태·예약 인원을 임의로 변경하지 않는다.
SELECT
    user_id,
    attraction_slot_id,
    COUNT(*) AS reservation_count
FROM reservations
WHERE reservation_status = 'RESERVED'
GROUP BY user_id, attraction_slot_id
HAVING COUNT(*) > 1;

-- 3. Generated Column과 활성 예약 유니크 제약을 함께 추가한다.
-- RESERVED는 1로 계산하여 동일 회원·슬롯의 활성 예약을 1건만 허용한다.
-- 그 외 상태는 NULL로 계산하여 취소 등의 이력을 여러 건 보존할 수 있게 한다.
-- Entity의 columnDefinition과 동일하게 기본 VIRTUAL Generated Column을 사용한다.
ALTER TABLE reservations
    ADD COLUMN active_reservation INT GENERATED ALWAYS AS (
        CASE WHEN reservation_status = 'RESERVED' THEN 1 ELSE NULL END
    ),
    ADD CONSTRAINT uk_reservations_user_slot_active
        UNIQUE (user_id, attraction_slot_id, active_reservation);

-- 4. 계산식과 유니크 인덱스가 생성됐는지 확인한다.
-- SHOW CREATE TABLE에서 Generated Column 정의와 유니크 키의 세 컬럼을 확인한다.
-- SHOW INDEX에서 Non_unique=0, Seq_in_index=1~3과 컬럼 순서를 확인한다.
SHOW CREATE TABLE reservations;
SHOW INDEX FROM reservations
WHERE Key_name = 'uk_reservations_user_slot_active';

-- 5. 기존 데이터의 계산 결과를 확인한다.
-- RESERVED는 1, 나머지 상태는 NULL이어야 한다. 예약 데이터가 없으면 결과도 0건이다.
SELECT
    reservation_status,
    active_reservation,
    COUNT(*) AS reservation_count
FROM reservations
GROUP BY reservation_status, active_reservation
ORDER BY reservation_status, active_reservation;
