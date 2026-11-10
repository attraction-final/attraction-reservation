-- =====================================================================
-- 로컬 개발용 테스트 데이터 (마이그레이션 아님)
-- ※ 내 컴퓨터 DB에서만 실행한다. (팀 공용 DB / 운영 DB 금지)
-- ※ 파일 전체를 한 번에 실행하지 않고, 필요한 단락만 골라 실행한다.
-- ※ DB 이름(attraction_reservation)을 테이블 앞에 붙여 두어 USE 문 없이 동작한다.
--    로컬 DB 이름이 다르면 파일 전체에서 attraction_reservation 을 바꿔서 실행한다.
-- =====================================================================


-- =====================================================================
-- A. 어트랙션 10개 + 오늘부터 3일치 슬롯
--    이름을 frontend/public/images 사진과 맞춰 두어 화면에 사진도 함께 보인다.
-- =====================================================================

-- A-1. 어트랙션 10개
-- 활성 어트랙션 이름은 유일해야 하므로(uk_attraction_name_active, KAN-103)
-- 같은 이름이 이미 있으면 실패한다. 먼저 아래 SELECT 결과가 0건인지 확인한다.
SELECT name
FROM attraction_reservation.attractions
WHERE deleted_at IS NULL
  AND name IN ('T 익스프레스', '롤링 엑스 트레인', '아마존 익스프레스', '썬더폴스', '사파리 월드',
               '슈팅 고스트', '회전 디스크', '윈드밀', '해적선 회전배', '범퍼카');

INSERT INTO attraction_reservation.attractions
(name, description, location, duration_minutes, entrance_move_minutes,
 operation_status, deleted_at, created_at, updated_at)
VALUES
    ('T 익스프레스',      '나무로 만든 초고속 롤러코스터',   '어드벤처존', 3,  15, 'OPERATING',   NULL, NOW(6), NOW(6)),
    ('롤링 엑스 트레인',  '360도 회전 루프 롤러코스터',     '스카이존',   4,  12, 'OPERATING',   NULL, NOW(6), NOW(6)),
    ('아마존 익스프레스', '보트를 타고 급류를 내려가요',    '레이크존',   12, 10, 'OPERATING',   NULL, NOW(6), NOW(6)),
    ('썬더폴스',          '높은 곳에서 물로 떨어지는 보트',  '레이크존',   5,  10, 'MAINTENANCE', NULL, NOW(6), NOW(6)),
    ('사파리 월드',       '버스를 타고 동물을 가까이 봐요',  '사파리존',   15, 8,  'OPERATING',   NULL, NOW(6), NOW(6)),
    ('슈팅 고스트',       '유령을 맞히는 실내 슈팅 게임',    '캐슬존',     15, 6,  'OPERATING',   NULL, NOW(6), NOW(6)),
    ('회전 디스크',       '빙글빙글 도는 원반 놀이기구',     '스카이존',   3,  12, 'OPERATING',   NULL, NOW(6), NOW(6)),
    ('윈드밀',            '풍차처럼 크게 회전하는 놀이기구', '스카이존',   3,  12, 'OPERATING',   NULL, NOW(6), NOW(6)),
    ('해적선 회전배',     '앞뒤로 크게 흔들리는 해적선',     '키즈존',     5,  5,  'OPERATING',   NULL, NOW(6), NOW(6)),
    ('범퍼카',            '서로 부딪히며 타는 자동차',       '키즈존',     5,  5,  'OPERATING',   NULL, NOW(6), NOW(6));

-- A-2. 슬롯: 어트랙션마다 10:00 ~ 21:30 시작, 30분 간격, 정원 20명, 오늘 포함 3일치
-- 종료 시각 = 시작 시각 + 탑승 시간(duration_minutes)  (AttractionSlot.calculateEndsAt, BR-SLT-01)
-- 날짜는 MySQL 시간대 설정과 상관없이 한국 날짜(UTC + 9시간)로 계산한다.
-- 같은 시각 슬롯이 이미 있으면 건너뛰므로 여러 번 실행해도 안전하다. (날짜가 지나면 다시 실행)
INSERT INTO attraction_reservation.attraction_slots
(attraction_id, starts_at, ends_at, capacity, reserved_count,
 status, auto_closed, created_at, updated_at)
SELECT a.id,
    TIMESTAMP(d.day, MAKETIME(h.hh, m.mm, 0))                                      AS starts_at,
    TIMESTAMP(d.day, MAKETIME(h.hh, m.mm, 0)) + INTERVAL a.duration_minutes MINUTE AS ends_at,
    20, 0, 'OPEN', 0, NOW(6), NOW(6)
FROM attraction_reservation.attractions a
    CROSS JOIN (SELECT DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR) + INTERVAL n DAY AS day
    FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2) x) d
    CROSS JOIN (SELECT 10 AS hh UNION ALL SELECT 11 UNION ALL SELECT 12 UNION ALL SELECT 13
    UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16 UNION ALL SELECT 17
    UNION ALL SELECT 18 UNION ALL SELECT 19 UNION ALL SELECT 20 UNION ALL SELECT 21) h
    CROSS JOIN (SELECT 0 AS mm UNION ALL SELECT 30) m
WHERE a.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM attraction_reservation.attraction_slots s
    WHERE s.attraction_id = a.id
  AND s.starts_at = TIMESTAMP(d.day, MAKETIME(h.hh, m.mm, 0))
    );

-- A-3. 확인
SELECT COUNT(*) AS attraction_count
FROM attraction_reservation.attractions
WHERE deleted_at IS NULL;

SELECT COUNT(*) AS slot_count
FROM attraction_reservation.attraction_slots;


-- =====================================================================
-- B. 내 계정을 관리자로 바꾸기
--    회원가입하면 role 은 항상 USER 로 저장된다. (User.java)
--    관리자 화면(/admin/**)을 쓰려면 ADMIN 이어야 한다.
--    ※ 바꾼 뒤에는 로그아웃 후 다시 로그인한다.
-- =====================================================================

-- 이메일을 내가 가입한 관리자용 이메일로 바꾼다.
UPDATE attraction_reservation.users
SET role = 'ADMIN'
WHERE email = 'admin@example.com';

SELECT id, email, role
FROM attraction_reservation.users
WHERE email = 'admin@example.com';


-- =====================================================================
-- C. 내 계정에 "오늘 입장한(IN_USE) 종일권" 1장 만들기
--    어트랙션 예약에는 입장 처리된 오늘 날짜 입장권이 필요하다. (ReservationService 규칙)
--    원래는 주문 → 결제(PortOne) → 관리자 입장 처리를 거쳐야 하지만,
--    결제 없이 예약 기능을 시험할 때 DB에 직접 만든다.
--    ※ 아래 @uid 줄의 이메일을 내가 가입한 이메일로 바꾼다.
--    ※ 결제(payments) 데이터가 없으므로 이 입장권은 환불 테스트에 쓸 수 없다.
--
--    이메일은 변수에 담지 않고 직접 적는다.
--    변수에 담으면 테이블과 글자 비교 규칙(collation)이 달라 "Illegal mix of collations" 오류가 날 수 있다.
-- =====================================================================

SET @uid   := (SELECT id FROM attraction_reservation.users WHERE email = 'test2@example.com');
SET @today := DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR);   -- 한국 날짜

-- C-1. 종일권 상품이 없으면 하나 만든다. (있으면 아무것도 하지 않는다)
INSERT INTO attraction_reservation.ticket_products
(name, price, description, type, stock, status, created_at, updated_at)
SELECT '종일권', 50000, '개장부터 폐장까지 이용할 수 있어요', 'ALL_DAY', 1000, 'ON_SALE', NOW(6), NOW(6)
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM attraction_reservation.ticket_products WHERE type = 'ALL_DAY'
);

SET @pid := (SELECT id FROM attraction_reservation.ticket_products WHERE type = 'ALL_DAY' ORDER BY id LIMIT 1);

-- C-2. 결제 완료된 주문 1건
INSERT INTO attraction_reservation.orders
(user_id, total_amount, status, visit_date, created_at, updated_at)
VALUES (@uid, 50000, 'COMPLETED', @today, NOW(6), NOW(6));
SET @oid := LAST_INSERT_ID();

-- C-3. 주문 상품 1줄
INSERT INTO attraction_reservation.order_items
(order_id, ticket_product_id, price, quantity)
VALUES (@oid, @pid, 50000, 1);
SET @oiid := LAST_INSERT_ID();

-- C-4. 오늘 날짜, 입장 완료(IN_USE) 상태의 입장권
INSERT INTO attraction_reservation.tickets
(valid_date, status, code, name, type, entered_at, end_time, order_item_id, created_at, updated_at)
VALUES (@today, 'IN_USE', CONCAT('TKT-', UUID()), '종일권', 'ALL_DAY', NOW(6), NULL, @oiid, NOW(6), NOW(6));

-- C-5. 확인: 내 입장권 목록
SELECT t.id AS ticket_id, t.valid_date, t.status, t.type
FROM attraction_reservation.tickets t
         JOIN attraction_reservation.order_items oi ON oi.id = t.order_item_id
         JOIN attraction_reservation.orders o ON o.id = oi.order_id
WHERE o.user_id = @uid;