-- KAN-150 전용 DB의 현재 데이터 잠금 대기 관계만 집계한다.
-- SQL 원문, LOCK_DATA, 사용자 값은 수집하지 않는다. 대기 시간이나 누적 횟수가 아니다.
SELECT /*+ MAX_EXECUTION_TIME(1000) */ JSON_OBJECT(
    'sampledAtUtc', DATE_FORMAT(UTC_TIMESTAMP(3), '%Y-%m-%dT%H:%i:%s.%fZ'),
    'waitEdges', COUNT(*),
    'waitingTransactions', COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID),
    'slotWaitEdges', COALESCE(SUM(l.OBJECT_NAME = 'attraction_slots'), 0),
    'attractionWaitEdges', COALESCE(SUM(l.OBJECT_NAME = 'attractions'), 0),
    'userWaitEdges', COALESCE(SUM(l.OBJECT_NAME = 'users'), 0),
    'otherWaitEdges', COALESCE(SUM(l.OBJECT_NAME NOT IN ('attraction_slots', 'attractions', 'users')), 0)
)
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks l
  ON l.ENGINE = w.ENGINE AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
WHERE l.OBJECT_SCHEMA = 'reservation_create_perf';
