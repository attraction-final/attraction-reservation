-- KAN-133 관리자 예약 목록의 상태 조건과 기본 정렬을 위한 복합 인덱스
-- Reservation의 @Index와 이름·컬럼 순서를 일치시킨다.
-- 회원 조건 없는 목록의 status DESC, id DESC 정렬과 상태 검색의 실행 계획을 비교한다.
CREATE INDEX idx_reservations_status_id
    ON reservations (reservation_status, id);
