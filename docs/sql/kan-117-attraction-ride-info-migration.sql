-- 어트랙션 Q&A 탑승 정보: 키 제한과 운영 시간
-- 목적: Q&A 답변의 숫자를 Jev가 아니라 DB 값으로 만든다.
-- 선행 조건: attractions 테이블에 아래 2번의 어트랙션 10개가 같은 이름으로 등록되어 있어야 한다.
-- 적용 순서: 탑승 정보 필드가 들어간 애플리케이션을 배포하기 전에 적용한다.
--            운영은 ddl-auto=validate라 컬럼이 없으면 애플리케이션이 기동되지 않는다.
-- MySQL DDL은 일반 트랜잭션 롤백으로 되돌릴 수 없다. 1번은 컬럼이 없는 스키마에 한 번만 적용한다.
-- 2번은 같은 값으로 덮어쓰는 UPDATE라 여러 번 실행해도 결과가 같다.

-- 1. 탑승 정보 컬럼 추가
--    전부 NULL을 허용한다. NULL은 "제한 없음"이 아니라 "등록된 정보 없음"이다.
ALTER TABLE attractions
    ADD COLUMN min_height_cm      INT  NULL,   -- 혼자 탈 수 있는 최소 키
    ADD COLUMN max_height_cm      INT  NULL,   -- 탈 수 있는 최대 키
    ADD COLUMN guardian_height_cm INT  NULL,   -- 이 키 이하는 보호자와 함께 타야 한다
    ADD COLUMN open_time          TIME NULL,   -- 운영 시작 시간
    ADD COLUMN close_time         TIME NULL;   -- 운영 종료 시간

-- 2. 어트랙션별 탑승 정보 입력
--    id 대신 이름으로 찾는다. (DB마다 어트랙션 id가 다를 수 있음)

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


SELECT id, name, min_height_cm, max_height_cm, guardian_height_cm, open_time, close_time
FROM attractions
WHERE deleted_at IS NULL
ORDER BY id;