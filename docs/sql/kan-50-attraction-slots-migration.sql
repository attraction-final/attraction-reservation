-- KAN-50 attraction_slots 조회 성능 개선용 복합 인덱스 추가
-- 대상 조회: 날짜별 슬롯 조회, 슬롯 겹침 검사, 어트랙션 목록의 nextSlot 조회
-- MySQL DDL은 일반 트랜잭션 롤백으로 되돌릴 수 없다.
-- 인덱스가 없는 기존 스키마에 한 번만 적용하는 스크립트다.
--   (MySQL은 CREATE INDEX IF NOT EXISTS를 지원하지 않아서, 두 번 실행하면 중복 이름 에러가 난다.)

-- 1. (attraction_id, starts_at) 순서로 복합 인덱스를 만든다.
--    컬럼 순서가 중요하다: "어트랙션 → 시작 시간" 순으로 찾기 때문에 attraction_id가 앞에 와야 한다.
--    이 인덱스가 생기면 MySQL이 FK용으로 자동 생성했던 attraction_id 단일 인덱스는
--    자동으로 제거될 수 있다. 새 인덱스의 맨 앞이 attraction_id라 FK 검사에 그대로 쓰인다.
CREATE INDEX idx_attraction_slots_attraction_starts
    ON attraction_slots (attraction_id, starts_at);

-- 2. 적용 확인: Key_name이 idx_attraction_slots_attraction_starts인 행이
--    Seq_in_index 1 = attraction_id, 2 = starts_at 으로 나오면 성공이다.
SHOW INDEX FROM attraction_slots;