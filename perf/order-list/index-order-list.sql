-- 주문 목록 복합 인덱스 실험용 SQL
-- 대상: 개인 성능 테스트 DB attraction_perf
-- 공유 DB 적용은 별도 검토 후 결정한다.
--
-- 실행 전 SHOW INDEX FROM orders로 기존 인덱스를 확인한다.
-- 같은 이름의 인덱스가 있으면 다시 실행하지 않는다.
-- 애플리케이션에서 자동 실행하는 파일이 아니다.

USE attraction_perf;

CREATE INDEX idx_orders_user_created_id
    ON orders (user_id, created_at DESC, id DESC);