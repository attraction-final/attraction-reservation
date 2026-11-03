-- KAN-95 관리자 상품 수정 시 오래된 요청을 감지하기 위한 version 컬럼 추가
-- 기존 ticket_products 테이블이 있고 version 컬럼이 없는 MySQL DB에 한 번 적용한다.
-- 적용 전 관리자 수정·삭제, 주문 생성·취소, 결제 실패 및 환불의 상품 변경 작업을 중지한다.
-- ticket_products 테이블을 백업하고, version을 갱신하지 않는 구버전 애플리케이션과 동시 운영하지 않는다.
-- MySQL DDL은 implicit commit이 발생하므로 일반 transaction rollback으로 되돌릴 수 없다.

-- 파일 전체를 한 번에 실행하지 않고 단계별 결과를 확인한 뒤 다음 단계로 진행한다.
-- 아래 확인 SQL은 조건이 맞지 않아도 이후 ALTER TABLE 실행을 자동으로 중단하지 않는다.

-- 1. 적용 대상 DB와 기존 테이블 구조를 확인한다.
-- 선택한 DB가 없거나 잘못된 DB인 경우, 또는 ticket_products 테이블이 없는 경우 중단한다.
SELECT DATABASE() AS target_database;
SHOW CREATE TABLE ticket_products;

-- 2. version 컬럼이 없는지 확인하고 기존 상품 수를 기록한다.
-- 컬럼 조회 결과가 0건일 때만 3단계로 진행한다.
-- 이미 컬럼이 있으면 재실행하거나 기존 version 값을 0으로 초기화하지 않는다.
SELECT
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE,
    COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ticket_products'
  AND COLUMN_NAME = 'version';

SELECT COUNT(*) AS product_count_before
FROM ticket_products;

-- 3. Java의 Long에 대응하는 BIGINT 컬럼을 추가한다.
-- 기존 상품은 version 0으로 시작하며, 이후 변경 시 JPA의 @Version이 값을 관리한다.
-- 기존 상품의 재고·가격·판매 상태는 변경하지 않는다.
ALTER TABLE ticket_products
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 4. 컬럼 정의와 기존 상품의 초기값을 확인한다.
-- version이 BIGINT NOT NULL DEFAULT 0으로 생성됐는지 확인한다.
SHOW CREATE TABLE ticket_products;

-- 상품 수가 2단계와 같고 invalid_initial_version_count가 0이어야 한다.
-- 쓰기 작업을 중지한 적용 직후의 검증이므로 모든 기존 상품의 version은 0이어야 한다.
SELECT
    COUNT(*) AS product_count_after,
    COALESCE(SUM(version IS NULL OR version <> 0), 0) AS invalid_initial_version_count
FROM ticket_products;

-- 검증 후 새 version을 사용하는 애플리케이션을 기동하고 상품 변경 작업을 재개한다.
-- 작업 재개 이후에는 version이 증가하므로 위 초기값 검증 조건을 다시 적용하지 않는다.
