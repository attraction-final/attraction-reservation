-- KAN-103 삭제되지 않은 어트랙션의 이름 중복 방지
-- 정책: 삭제된 어트랙션의 이름은 재사용할 수 있다.
-- 수동 적용용 SQL(docs/sql/kan-103-attraction-name-unique-migration.sql)의 3단계와 같다.
--
-- 삭제되지 않은 행은 1, 삭제된 행은 NULL로 계산한다.
-- MySQL UNIQUE는 NULL을 포함하는 키의 중복을 허용하므로 같은 이름의 삭제 이력을 여러 건 보존할 수 있다.
-- 엔티티 매핑(Attraction.activeAttraction)과 같이 기본 VIRTUAL Generated Column을 사용한다.
--
-- 삭제되지 않은 어트랙션 중 이름이 같은 행이 있으면 UNIQUE 제약 추가가 실패한다.
-- 운영 적용 전 확인: attractions 0건, 중복 0건 (2026-11-05)
ALTER TABLE attractions
    ADD COLUMN active_attraction INT GENERATED ALWAYS AS (
        CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END
    ),
    ADD CONSTRAINT uk_attraction_name_active
        UNIQUE (name, active_attraction);
