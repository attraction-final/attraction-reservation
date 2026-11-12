-- KAN-97 추천 경로 이동 데이터: 어트랙션 간 이동 거리·시간 + 어트랙션 좌표
-- 목적: 추천 경로에서 "직전 어트랙션 → 다음 어트랙션" 실측 이동 시간을 사용하고,
--       실측값이 없는 쌍과 사용자 위치 → 첫 어트랙션 구간은 좌표로 계산하기 위한 데이터
-- 적용 후 상태: attraction_paths 90건 (어트랙션 10개 × 출발지별 9개 도착지), attraction_locations 10건
-- 이동 거리/시간: 네이버 지도 도보 길찾기 측정값 (원본 그대로 저장, 보정은 애플리케이션에서 별도 결정)
-- 선행 조건: attractions 테이블에 아래 3번의 어트랙션 10개가 같은 이름으로 등록되어 있어야 한다.
-- MySQL DDL은 일반 트랜잭션 롤백으로 되돌릴 수 없다. 테이블이 없는 스키마에 한 번만 적용한다.

-- 1. 어트랙션 간 이동 구간 테이블
--    한 행 = 출발 어트랙션 → 도착 어트랙션 (방향별로 한 행씩 저장)
CREATE TABLE attraction_paths (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    from_attraction_id  BIGINT       NOT NULL,                 -- 출발 어트랙션
    to_attraction_id    BIGINT       NOT NULL,                 -- 도착 어트랙션
    distance_m          INT          NOT NULL,                 -- 도보 이동 거리 (m, 네이버 지도 기준)
    move_minutes        INT          NOT NULL,                 -- 도보 이동 시간 (분, 네이버 지도 기준)
    is_estimated        BOOLEAN      NOT NULL DEFAULT FALSE,   -- TRUE = 실측이 아닌 근사값
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,    -- FALSE = 공사 등으로 일시 폐쇄
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_attraction_paths_from FOREIGN KEY (from_attraction_id) REFERENCES attractions (id),
    CONSTRAINT fk_attraction_paths_to   FOREIGN KEY (to_attraction_id)   REFERENCES attractions (id),
    -- 같은 방향의 구간 중복 저장 방지. 복합 인덱스의 맨 앞이 from_attraction_id라
    -- "이 어트랙션에서 출발하는 구간" 조회(WHERE from_attraction_id = ?)에도 그대로 쓰인다.
    CONSTRAINT uk_attraction_paths_from_to UNIQUE (from_attraction_id, to_attraction_id),
    CONSTRAINT ck_attraction_paths_not_self CHECK (from_attraction_id <> to_attraction_id),
    CONSTRAINT ck_attraction_paths_distance CHECK (distance_m > 0),
    CONSTRAINT ck_attraction_paths_minutes  CHECK (move_minutes > 0)
);

-- 2. 어트랙션 좌표 테이블
--    attractions 테이블에 컬럼을 추가하지 않고 별도 테이블로 둔다.
--    좌표는 추천 경로 기능만 쓰는 데이터라, 어트랙션 도메인을 건드리지 않고 경로 도메인 안에서 관리하기 위함.
--    위도/경도는 DOUBLE: Java 하버사인 계산이 double을 받고, 엔티티 타입과 일치해 ddl-auto=validate를 통과한다.
CREATE TABLE attraction_locations (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    attraction_id  BIGINT       NOT NULL,
    latitude       DOUBLE       NOT NULL,   -- 위도 (WGS84)
    longitude      DOUBLE       NOT NULL,   -- 경도 (WGS84)
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_attraction_locations_attraction UNIQUE (attraction_id),
    CONSTRAINT fk_attraction_locations_attraction FOREIGN KEY (attraction_id) REFERENCES attractions (id),
    CONSTRAINT ck_attraction_locations_latitude  CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT ck_attraction_locations_longitude CHECK (longitude BETWEEN -180 AND 180)
);

-- 3. 어트랙션 id 조회
--    attractions.name이 실제 DB 값과 다르면 변수가 NULL이 되고, 아래 INSERT가 NOT NULL 제약으로 실패한다.
--    (잘못된 id로 조용히 들어가는 것을 막음) 실행 전에 이름을 실제 DB 값에 맞게 확인할 것.
SET @bumper_car        := (SELECT id FROM attractions WHERE name = '범퍼카' AND deleted_at IS NULL);
SET @space_fighter     := (SELECT id FROM attractions WHERE name = '우주전투기' AND deleted_at IS NULL);
SET @amazon            := (SELECT id FROM attractions WHERE name = '아마존 익스프레스' AND deleted_at IS NULL);
SET @monimo            := (SELECT id FROM attractions WHERE name = '모니모 러쉬' AND deleted_at IS NULL);
SET @thunder_falls     := (SELECT id FROM attractions WHERE name = '썬더폴스' AND deleted_at IS NULL);
SET @columbus          := (SELECT id FROM attractions WHERE name = '콜럼버스 대탐험' AND deleted_at IS NULL);
SET @double_rock_spin  := (SELECT id FROM attractions WHERE name = '더블 락스핀' AND deleted_at IS NULL);
SET @lets_twist        := (SELECT id FROM attractions WHERE name = '렛츠 트위스트' AND deleted_at IS NULL);
SET @rolling_x_train   := (SELECT id FROM attractions WHERE name = '롤링 엑스 트레인' AND deleted_at IS NULL);
SET @hurricane         := (SELECT id FROM attractions WHERE name = '허리케인' AND deleted_at IS NULL);

-- 4. 어트랙션 좌표 10건
--    주의: 모니모 러쉬 / 썬더폴스 / 우주전투기 좌표는 소수점 4자리로 반올림된 값이고,
--          도보 거리보다 직선거리가 더 길게 나오는 구간이 있어 재측정이 필요하다.
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@bumper_car, 37.2923658, 127.1995663);  -- 범퍼카
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@space_fighter, 37.2929, 127.2024);  -- 우주전투기
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@amazon, 37.2912666, 127.2057353);  -- 아마존 익스프레스
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@monimo, 37.29, 127.2025);  -- 모니모 러쉬
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@thunder_falls, 37.2936, 127.2036);  -- 썬더폴스
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@columbus, 37.2945, 127.19957);  -- 콜럼버스 대탐험
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@double_rock_spin, 37.29299, 127.20199);  -- 더블 락스핀
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@lets_twist, 37.29272, 127.20084);  -- 렛츠 트위스트
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@rolling_x_train, 37.29293, 127.20141);  -- 롤링 엑스 트레인
INSERT INTO attraction_locations (attraction_id, latitude, longitude) VALUES (@hurricane, 37.29397, 127.20161);  -- 허리케인

-- 5. 어트랙션 간 이동 구간 90건 (네이버 지도 도보 길찾기 측정값)

-- 범퍼카 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @space_fighter, 322, 5);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @amazon, 641, 11);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @monimo, 632, 11);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @thunder_falls, 141, 3);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @columbus, 495, 12);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @double_rock_spin, 493, 11);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @lets_twist, 576, 12);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @rolling_x_train, 508, 11);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@bumper_car, @hurricane, 342, 9);  -- → 허리케인

-- 우주전투기 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @bumper_car, 322, 5);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @amazon, 433, 10);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @monimo, 424, 9);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @thunder_falls, 452, 8);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @columbus, 648, 13);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @double_rock_spin, 301, 7);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @lets_twist, 273, 6);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @rolling_x_train, 265, 6);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@space_fighter, @hurricane, 431, 9);  -- → 허리케인

-- 아마존 익스프레스 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @bumper_car, 641, 12);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @space_fighter, 433, 10);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @monimo, 217, 3);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @thunder_falls, 808, 16);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @columbus, 1100, 24);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @double_rock_spin, 697, 17);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @lets_twist, 669, 16);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @rolling_x_train, 661, 16);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@amazon, @hurricane, 827, 19);  -- → 허리케인

-- 모니모 러쉬 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @bumper_car, 632, 12);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @space_fighter, 424, 10);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @amazon, 217, 3);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @thunder_falls, 799, 16);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @columbus, 1100, 24);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @double_rock_spin, 688, 16);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @lets_twist, 660, 16);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @rolling_x_train, 653, 16);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@monimo, @hurricane, 818, 19);  -- → 허리케인

-- 썬더폴스 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @bumper_car, 141, 3);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @space_fighter, 452, 8);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @amazon, 808, 14);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @monimo, 799, 14);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @columbus, 424, 9);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @double_rock_spin, 421, 8);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @lets_twist, 505, 9);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @rolling_x_train, 436, 8);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@thunder_falls, @hurricane, 271, 6);  -- → 허리케인

-- 콜럼버스 대탐험 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @bumper_car, 495, 9);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @space_fighter, 648, 11);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @amazon, 1100, 21);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @monimo, 1100, 21);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @thunder_falls, 424, 7);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @double_rock_spin, 368, 6);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @lets_twist, 451, 7);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @rolling_x_train, 383, 6);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@columbus, @hurricane, 218, 4);  -- → 허리케인

-- 더블 락스핀 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @bumper_car, 493, 9);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @space_fighter, 301, 5);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @amazon, 697, 14);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @monimo, 688, 14);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @thunder_falls, 421, 7);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @columbus, 368, 6);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @lets_twist, 104, 1);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @rolling_x_train, 48, 1);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@double_rock_spin, @hurricane, 150, 2);  -- → 허리케인

-- 렛츠 트위스트 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @bumper_car, 576, 11);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @space_fighter, 273, 5);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @amazon, 669, 14);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @monimo, 660, 14);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @thunder_falls, 505, 9);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @columbus, 451, 8);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @double_rock_spin, 104, 1);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @rolling_x_train, 68, 1);  -- → 롤링 엑스 트레인
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@lets_twist, @hurricane, 234, 4);  -- → 허리케인

-- 롤링 엑스 트레인 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @bumper_car, 508, 9);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @space_fighter, 265, 5);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @amazon, 661, 14);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @monimo, 653, 14);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @thunder_falls, 436, 7);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @columbus, 383, 7);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @double_rock_spin, 48, 1);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @lets_twist, 68, 1);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@rolling_x_train, @hurricane, 165, 3);  -- → 허리케인

-- 허리케인 출발
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @bumper_car, 342, 6);  -- → 범퍼카
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @space_fighter, 431, 7);  -- → 우주전투기
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @amazon, 827, 16);  -- → 아마존 익스프레스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @monimo, 818, 16);  -- → 모니모 러쉬
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @thunder_falls, 271, 4);  -- → 썬더폴스
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @columbus, 218, 4);  -- → 콜럼버스 대탐험
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @double_rock_spin, 150, 2);  -- → 더블 락스핀
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @lets_twist, 234, 3);  -- → 렛츠 트위스트
INSERT INTO attraction_paths (from_attraction_id, to_attraction_id, distance_m, move_minutes) VALUES (@hurricane, @rolling_x_train, 165, 2);  -- → 롤링 엑스 트레인

-- 6. 적용 확인 1: 90이 나와야 한다.
SELECT COUNT(*) AS path_count FROM attraction_paths;

-- 7. 적용 확인 2: 10개 어트랙션 사이에 빠진 (출발, 도착) 쌍이 있으면 출력된다. 결과가 0행이어야 한다.
SELECT f.name AS from_name, t.name AS to_name
FROM attractions f
JOIN attractions t ON t.id <> f.id
LEFT JOIN attraction_paths p
       ON p.from_attraction_id = f.id
      AND p.to_attraction_id   = t.id
WHERE f.id IN (@bumper_car, @space_fighter, @amazon, @monimo, @thunder_falls, @columbus, @double_rock_spin, @lets_twist, @rolling_x_train, @hurricane)
  AND t.id IN (@bumper_car, @space_fighter, @amazon, @monimo, @thunder_falls, @columbus, @double_rock_spin, @lets_twist, @rolling_x_train, @hurricane)
  AND p.id IS NULL;

-- 8. 적용 확인 3: 어트랙션 10개의 좌표가 나와야 한다.
SELECT a.id, a.name, l.latitude, l.longitude
FROM attraction_locations l
JOIN attractions a ON a.id = l.attraction_id
ORDER BY a.id;
