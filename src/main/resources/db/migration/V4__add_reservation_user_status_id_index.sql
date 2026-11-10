-- KAN-130 내 예약 목록의 회원·상태 조건과 기본 정렬을 위한 복합 인덱스
-- Reservation의 @Index와 이름·컬럼 순서를 일치시킨다.
-- 회원별 검색과 status DESC, id DESC 정렬의 실행 계획 개선 여부를 측정한다.
CREATE INDEX idx_reservations_user_status_id
    ON reservations (user_id, reservation_status, id);
