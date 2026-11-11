-- V14__update_attraction_ride_info.sql
-- KAN-XXX 어트랙션 탑승 정보 (키 제한, 운영 시간) 데이터
-- 원본: docs/sql/kan-117 2번 (데이터 부분)
-- 컬럼은 V3(add_attraction_ride_info_columns)에서 추가되므로 DDL은 넣지 않는다.
--
-- 설계 결정
-- 1) id 대신 이름으로 찾는다. (DB마다 어트랙션 id가 다를 수 있음)
--    대상 어트랙션이 없는 DB(빈 로컬, 테스트)에서는 0건이 변경되고 마이그레이션은 성공한다.
-- 2) 같은 값으로 덮어쓰는 UPDATE라, kan-117을 수동 실행한 적이 있는 DB에서도 결과가 같다.
-- 3) 확인용 SELECT는 넣지 않는다. (확인 쿼리는 docs/sql에 남겨 둔다)
--
-- NULL은 "제한 없음"이 아니라 "등록된 정보 없음"이다.
-- 주의: 이 파일은 UTF-8로 저장되어야 한다. 인코딩이 깨지면 이름 비교가 0건이 되어 조용히 아무것도 바뀌지 않는다.

-- 검토 필요: 보호자 기준이 110cm 이하라서 정확히 110cm면 단독 최소 키와 겹친다. 이때는 보호자 기준을 우선한다.
UPDATE attractions
SET min_height_cm = 110, guardian_height_cm = 110, open_time = '10:30:00', close_time = '20:00:00'
WHERE name = '아마존 익스프레스' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 130, max_height_cm = 195, open_time = '12:45:00', close_time = '19:30:00'
WHERE name = '모니모 러쉬' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 120, open_time = '10:00:00', close_time = '20:00:00'
WHERE name = '범퍼카' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 100, open_time = '10:30:00', close_time = '20:00:00'
WHERE name = '썬더폴스' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 110, open_time = '10:00:00', close_time = '13:00:00'
WHERE name = '우주전투기' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 110, open_time = '10:45:00', close_time = '20:00:00'
WHERE name = '콜럼버스 대탐험' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 140, open_time = '11:00:00', close_time = '20:00:00'
WHERE name = '더블 락스핀' AND deleted_at IS NULL;

-- 원본 표의 운영 시간이 "점검"이라 시간은 비워 둔다. 점검 상태는 관리자 API로 변경한다.
UPDATE attractions
SET min_height_cm = 140, max_height_cm = 195
WHERE name = '렛츠 트위스트' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 120, open_time = '10:00:00', close_time = '20:30:00'
WHERE name = '롤링 엑스 트레인' AND deleted_at IS NULL;

UPDATE attractions
SET min_height_cm = 130, open_time = '10:00:00', close_time = '20:30:00'
WHERE name = '허리케인' AND deleted_at IS NULL;