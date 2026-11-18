-- 웹훅 E2E 재현용 데이터. Flyway가 스키마와 티켓 상품(종일권 50,000원)을 만든 뒤 넣는다.
-- 주문 3건은 모두 결제 대기(PENDING)이고, 결제 결과는 WireMock(가짜 PortOne)이 정한다.
--   9001 / pay-e2e-001: 웹훅으로 결제 완료 반영 (시나리오 1~3)
--   9004 / pay-e2e-004: PG 장애 중 웹훅 수신 → 복구 뒤 재시도 (시나리오 4)
--   9005 / pay-e2e-005: 승인 API·웹훅 없이 40분 지난 주문, PG는 결제 완료 (시나리오 5)

INSERT INTO users (id, created_at, updated_at, email, nickname, password, provider, role, status)
VALUES (9001, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'e2e@example.com', 'e2e', NULL, 'LOCAL', 'USER', 'ACTIVE');

SET @product_id = (SELECT id FROM ticket_products WHERE type = 'ALL_DAY' AND status = 'ON_SALE' LIMIT 1);
SET @visit_date = DATE_ADD(UTC_DATE(), INTERVAL 7 DAY);

INSERT INTO orders (id, created_at, updated_at, status, total_amount, visit_date, user_id) VALUES
  (9001, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'PENDING', 50000, @visit_date, 9001),
  (9004, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'PENDING', 50000, @visit_date, 9001),
  (9005, UTC_TIMESTAMP(6) - INTERVAL 40 MINUTE, UTC_TIMESTAMP(6) - INTERVAL 40 MINUTE, 'PENDING', 50000, @visit_date, 9001);

INSERT INTO order_items (id, price, quantity, order_id, ticket_product_id) VALUES
  (9001, 50000, 1, 9001, @product_id),
  (9004, 50000, 1, 9004, @product_id),
  (9005, 50000, 1, 9005, @product_id);

INSERT INTO payments (id, created_at, updated_at, amount, paid_at, pg_id, status, order_id) VALUES
  (9001, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 50000, NULL, 'pay-e2e-001', 'PENDING', 9001),
  (9004, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 50000, NULL, 'pay-e2e-004', 'PENDING', 9004),
  (9005, UTC_TIMESTAMP(6) - INTERVAL 40 MINUTE, UTC_TIMESTAMP(6) - INTERVAL 40 MINUTE, 50000, NULL, 'pay-e2e-005', 'PENDING', 9005);
