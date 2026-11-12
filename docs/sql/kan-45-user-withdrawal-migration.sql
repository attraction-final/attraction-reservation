-- KAN-45 회원 탈퇴 일시 컬럼 추가
-- 적용 전 users 테이블을 백업한다.
-- withdrawn_at 컬럼이 없는 스키마에 한 번 적용한다.
-- MySQL DDL은 일반 트랜잭션 롤백으로 되돌릴 수 없다.

ALTER TABLE users
    ADD COLUMN withdrawn_at DATETIME(6) NULL;