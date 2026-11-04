-- 개인 테스트 DB의 관리자 회원 목록 인덱스 실험.
-- 공유 DB 적용 여부는 전후 측정 후 별도로 검토한다.
USE attraction_perf;

CREATE INDEX idx_users_created_id_status
    ON users (created_at DESC, id DESC, status);

SHOW INDEX FROM users
WHERE Key_name = 'idx_users_created_id_status';