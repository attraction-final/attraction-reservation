-- KAN-69 소셜 회원 저장 구조 추가
-- 적용 전 애플리케이션을 중지하고 users 테이블을 백업한다.
-- MySQL DDL은 일반 트랜잭션 롤백으로 되돌릴 수 없다.
-- 컬럼이 없는 기존 스키마에 한 번 적용하는 스크립트다.

-- 1. 기존 회원 데이터가 있는 상태에서도 추가할 수 있도록 NULL을 허용한다.
ALTER TABLE users
    ADD COLUMN provider ENUM('LOCAL', 'KAKAO', 'GOOGLE') NULL,
    ADD COLUMN provider_user_id VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;

-- 2. 기존 이메일·비밀번호 회원은 LOCAL로 분류한다.
UPDATE users
SET provider = 'LOCAL'
WHERE provider IS NULL;

-- 3. 제공자를 필수로 변경하고 소셜 회원의 비밀번호는 NULL을 허용한다.
-- LOCAL 기본값은 기존 가입 코드의 INSERT와 호환하기 위해 설정한다.
ALTER TABLE users
    MODIFY COLUMN provider ENUM('LOCAL', 'KAKAO', 'GOOGLE')
    NOT NULL DEFAULT 'LOCAL',
    MODIFY COLUMN password VARCHAR(255)
    COLLATE utf8mb4_unicode_ci NULL,
    ADD CONSTRAINT uk_users_provider_user_id
    UNIQUE (provider, provider_user_id);