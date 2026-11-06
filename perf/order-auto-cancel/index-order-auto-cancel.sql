-- 자동 취소 대상 조회를 위한 실험용 복합 인덱스.
CREATE INDEX idx_orders_status_created_id
    ON attraction_auto_cancel_perf.orders (
                                           status,
                                           created_at,
                                           id
        );

SHOW INDEX
FROM attraction_auto_cancel_perf.orders
WHERE Key_name = 'idx_orders_status_created_id';