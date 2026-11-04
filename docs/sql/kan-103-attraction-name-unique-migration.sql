-- KAN-103 삭제되지 않은 어트랙션의 이름 중복 방지
-- 정책: 삭제된 어트랙션의 이름은 재사용할 수 있다.
--
-- 적용 전 어트랙션 쓰기 작업을 중지하고 테이블을 백업한다.
-- 파일 전체를 실행하지 않고 단계별 결과를 확인한다.
-- MySQL DDL은 일반 transaction rollback으로 되돌릴 수 없다.
-- 아래 확인 쿼리는 이후 ALTER 실행을 자동으로 중단하지 않는다.

-- 1. 적용 대상 DB와 테이블 구조를 확인한다.
-- 테이블이 없으면 여기서 중단한다.
SELECT DATABASE() AS target_database;

SHOW CREATE TABLE attractions;

SHOW INDEX FROM attractions;

-- 확인 사항:
-- active_attraction 컬럼 또는 동일 제약이 이미 있으면 ALTER를 실행하지 않는다.
-- name 전체의 UNIQUE 제약이 있다면 삭제된 이름의 재사용을 막으므로
-- 기존 제약의 처리 방안을 먼저 검토한다.


-- 2. 삭제되지 않은 어트랙션의 이름 중복을 확인한다.
-- 결과가 0건일 때만 다음 단계로 진행한다.
-- 중복이 있으면 유지할 이름과 변경 대상을 결정한 뒤 다시 확인한다.
-- 이 SQL은 기존 데이터를 임의로 수정하거나 삭제하지 않는다.
SELECT
    name,
    COUNT(*) AS attraction_count
FROM attractions
WHERE deleted_at IS NULL
GROUP BY name
HAVING COUNT(*) > 1;


-- 3. Generated Column과 UNIQUE 제약을 추가한다.
-- 삭제되지 않은 행은 1, 삭제된 행은 NULL로 계산한다.
-- MySQL UNIQUE는 NULL을 포함하는 키의 중복을 허용하므로
-- 같은 이름의 삭제 이력을 여러 건 보존할 수 있다.
-- 엔티티 매핑과 동일하게 기본 VIRTUAL Generated Column을 사용한다.
ALTER TABLE attractions
    ADD COLUMN active_attraction INT GENERATED ALWAYS AS (
        CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END
    ),
    ADD CONSTRAINT uk_attraction_name_active
        UNIQUE (name, active_attraction);


-- 4. 컬럼 계산식과 UNIQUE 인덱스를 확인한다.
SHOW CREATE TABLE attractions;

-- Non_unique=0이고, 컬럼 순서가 name → active_attraction인지 확인한다.
SHOW INDEX FROM attractions
WHERE Key_name = 'uk_attraction_name_active';


-- 5. 계산 결과가 올바른지 확인한다.
-- invalid_count는 0이어야 한다.
SELECT COUNT(*) AS invalid_count
FROM attractions
WHERE
    (deleted_at IS NULL AND
        (active_attraction IS NULL OR active_attraction <> 1))
    OR
    (deleted_at IS NOT NULL AND active_attraction IS NOT NULL);


-- 6. 적용 후 활성 이름 중복이 없는지 확인한다.
-- 결과가 0건이어야 한다.
SELECT
    name,
    COUNT(*) AS attraction_count
FROM attractions
WHERE deleted_at IS NULL
GROUP BY name
HAVING COUNT(*) > 1;
