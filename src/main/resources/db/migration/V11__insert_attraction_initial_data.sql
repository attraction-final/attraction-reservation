-- KAN-123 배포용 어트랙션 초기 데이터 (어트랙션 10개)
-- 수동 적용용 SQL(docs/sql/kan-123-attraction-initial-data.sql)의 3단계(INSERT)와 같은 값이다.
-- 확인용 SELECT(1·2·4단계)와 START TRANSACTION / COMMIT 은 넣지 않는다.
--   - Flyway가 이 파일을 트랜잭션 하나로 실행하므로, 실패하면 10개 모두 들어가지 않는다.
--   - 슬롯은 넣지 않는다. 앱이 뜬 뒤 슬롯 자동 생성 스케줄러가 오늘부터 30일치를 만든다.
--
-- 같은 이름의 삭제되지 않은 어트랙션이 이미 있으면 그 어트랙션은 건너뛴다. (NOT EXISTS)
--   - docs/sql 파일을 운영에 직접 실행한 DB, 로컬 시드(kan-92)를 넣은 DB에서도
--     V2의 이름 유일 제약(uk_attraction_name_active)에 걸려 기동이 실패하지 않게 하기 위함이다.
--   - 건너뛴 어트랙션의 기존 값(설명·구역 등)은 바꾸지 않는다.
--
-- 값의 근거와 [확인]/[추측] 표시는 docs/sql/kan-123-attraction-initial-data.sql 주석을 따른다.
-- 이 파일은 적용 후 수정하지 않는다. 값이 바뀌면 새 번호의 마이그레이션(UPDATE)으로 고친다. (docs/database/migration.md 1장)
INSERT INTO attractions
(name, description, location, duration_minutes, entrance_move_minutes,
 operation_status, deleted_at, created_at, updated_at)
SELECT seed.name, seed.description, seed.location, seed.duration_minutes, seed.entrance_move_minutes,
       seed.operation_status, NULL, NOW(6), NOW(6)
FROM (
         SELECT '범퍼카' AS name, '작은 자동차를 직접 몰며 서로 부딪히는 기구' AS description,
                '매직랜드' AS location, 5 AS duration_minutes, 5 AS entrance_move_minutes,
                'OPERATING' AS operation_status
         UNION ALL SELECT '우주전투기',        '조종간으로 위아래를 움직이며 도는 비행기 기구',       '매직랜드',          2,  10, 'OPERATING'
         UNION ALL SELECT '아마존 익스프레스', '원형 보트를 타고 580m 수로의 급류를 내려가는 기구',  '주토피아',          12, 10, 'OPERATING'
         UNION ALL SELECT '모니모 러쉬',       '나무와 스틸 트랙을 섞은 롤러코스터 (구 T익스프레스)', '유러피언 어드벤처', 3,  10, 'OPERATING'
         -- TODO: 운영 시간(10:30~20:00)이 있어 실제로는 운영 중일 수 있다. 확인 후 새 마이그레이션으로 OPERATING 변경.
         UNION ALL SELECT '썬더폴스',          '보트를 타고 급류를 지나 20m 높이에서 떨어지는 기구',  '아메리칸 어드벤처', 6,  10, 'MAINTENANCE'
         UNION ALL SELECT '콜럼버스 대탐험',   '큰 배가 앞뒤로 크게 흔들리는 스윙 기구',              '아메리칸 어드벤처', 3,  10, 'OPERATING'
         UNION ALL SELECT '더블 락스핀',       '회전하며 크게 흔들리는 스릴 기구',                    '아메리칸 어드벤처', 2,  10, 'OPERATING'
         -- 원본 표 "점검"
         UNION ALL SELECT '렛츠 트위스트',     '빙글빙글 비틀며 도는 회전 기구',                      '아메리칸 어드벤처', 2,  10, 'MAINTENANCE'
         UNION ALL SELECT '롤링 엑스 트레인',  '네 번 뒤집히는 루프·코르크스크루 롤러코스터',        '아메리칸 어드벤처', 2,  12, 'OPERATING'
         UNION ALL SELECT '허리케인',          '공중에서 크게 회전하는 스릴 기구',                    '아메리칸 어드벤처', 2,  10, 'OPERATING'
     ) AS seed
WHERE NOT EXISTS (
    SELECT 1
    FROM attractions a
    WHERE a.name = seed.name
      AND a.deleted_at IS NULL
);