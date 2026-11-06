-- KAN-92 ticket_products.status ENUM 에 OUT_OF_STOCK(품절) 추가
-- 이유: TicketProduct.decreaseStock() 은 재고가 0이 되면 status 를 OUT_OF_STOCK 으로 저장하는데,
--       기존 DB의 ENUM 목록에 OUT_OF_STOCK 이 없어 "Data truncated for column 'status'" 오류가 난다.
--       (마지막 입장권 주문 시 저장 실패로 이어질 수 있다)
-- 기존 ticket_products 테이블의 status 컬럼이 ENUM 이고 OUT_OF_STOCK 이 없는 MySQL DB에 한 번 적용한다.
-- MySQL DDL은 implicit commit이 발생하므로 일반 transaction rollback으로 되돌릴 수 없다.

-- 파일 전체를 한 번에 실행하지 않고 단계별 결과를 확인한 뒤 다음 단계로 진행한다.

-- 1. 적용 대상 DB와 현재 status 컬럼 정의를 확인한다.
-- 선택한 DB가 없거나 잘못된 DB인 경우 중단한다.
-- COLUMN_TYPE 에 이미 'OUT_OF_STOCK' 이 있으면 적용하지 않는다.
SELECT DATABASE() AS target_database;

SELECT
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ticket_products'
  AND COLUMN_NAME = 'status';

-- 2. 목록에 없는 값이 저장되어 빈 문자열('')이 된 상품이 없는지 확인한다.
-- 결과가 0건이 아니면 3단계 전에 해당 상품의 status 를 올바른 값(예: ON_SALE)으로 먼저 고친다.
-- 빈 문자열이 남아 있으면 3단계 ALTER TABLE 이 실패할 수 있다.
SELECT id, name, status
FROM ticket_products
WHERE status = '';

-- 3. Java TicketProductStatus enum 의 값 5개를 모두 허용하도록 컬럼을 변경한다.
-- 기존 상품의 status 값은 변경되지 않는다.
ALTER TABLE ticket_products
    MODIFY COLUMN status ENUM(
    'ON_SALE',
    'STOPPED',
    'ENDED',
    'DELETED',
    'OUT_OF_STOCK'
    ) NOT NULL;

-- 4. 컬럼 정의에 OUT_OF_STOCK 이 추가됐는지 확인한다.
SELECT
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ticket_products'
  AND COLUMN_NAME = 'status';