-- 1. 변환 중 기존 값과 새 값을 모두 허용한다.
ALTER TABLE orders
    MODIFY COLUMN status ENUM(
    'CANCELLED',
    'PAID',
    'COMPLETED',
    'PARTIALLY_REFUNDED',
    'PENDING',
    'REFUNDED'
    ) NOT NULL;

-- 2. 기존 주문 완료 데이터를 새 상태명으로 변환한다.
UPDATE orders
SET status = 'COMPLETED'
WHERE status = 'PAID';

-- 3. 변환 후 이전 상태값을 제거한다.
ALTER TABLE orders
    MODIFY COLUMN status ENUM(
    'CANCELLED',
    'COMPLETED',
    'PARTIALLY_REFUNDED',
    'PENDING',
    'REFUNDED'
    ) NOT NULL;