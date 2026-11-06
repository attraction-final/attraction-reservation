-- KAN-128 어트랙션 Q&A 탑승 정보 컬럼 (KAN-117 엔티티 필드에 대응)
-- 수동 적용용 SQL(docs/sql/kan-117-attraction-ride-info-migration.sql)의 1단계와 같다.
-- 탑승 정보 값(2단계 UPDATE)은 넣지 않는다. 운영 배포 후 docs/sql 파일로 적용한다.
--
-- 전부 NULL을 허용한다. NULL은 "제한 없음"이 아니라 "등록된 정보 없음"이다.
-- NULL 허용 컬럼 추가라 배포 중 이전 버전 앱도 그대로 동작한다. (docs/database/migration.md 3장)
--
-- docs/sql 파일의 1단계를 직접 실행한 DB에서는 Duplicate column name으로 실패한다.
-- 운영 적용 전 attractions에 이 컬럼들이 없는지 확인한다.
ALTER TABLE attractions
    ADD COLUMN min_height_cm      INT  NULL,
    ADD COLUMN max_height_cm      INT  NULL,
    ADD COLUMN guardian_height_cm INT  NULL,
    ADD COLUMN open_time          TIME NULL,
    ADD COLUMN close_time         TIME NULL;
