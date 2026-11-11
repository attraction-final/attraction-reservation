-- V13__insert_route_travel_data.sql
-- KAN-161 추천 경로 이동 데이터 (좌표 10건 + 구간 90건)
-- 원본: docs/sql/kan-97 데이터 부분 + kan-98 좌표 보정 (= docs/sql/kan-136과 같은 최종값)
-- 테이블 구조는 V6(attraction_locations), V7(attraction_paths)가 이미 만들었으므로 DDL은 넣지 않는다.
--
-- 설계 결정
-- 1) 어트랙션 id를 SET @변수로 잡지 않고, 이름으로 JOIN해서 넣는다.
--    attractions에 해당 이름이 없는 DB(빈 로컬, 테스트)에서는 0건이 들어가고 마이그레이션은 성공한다.
--    (SET @변수 방식은 NULL INSERT로 실패 → Flyway 실패 → 애플리케이션 기동 실패)
-- 2) ON DUPLICATE KEY UPDATE로 넣는다.
--    공용 DB처럼 kan-97/kan-98/kan-136을 수동 실행한 적이 있는 DB에서도 UNIQUE 위반 없이 같은 최종값으로 맞춰진다.
-- 3) 확인용 SELECT는 넣지 않는다. 자동 실행에서는 아무도 결과를 보지 않는다. (확인 쿼리는 docs/sql에 남겨 둔다)
--
-- 주의: 이 파일은 UTF-8로 저장되어야 한다. 인코딩이 깨지면 이름 JOIN이 0건이 되어 '조용히' 아무것도 안 들어간다.

-- 1. 어트랙션 좌표 10건 (WGS84)
INSERT INTO attraction_locations (created_at, updated_at, attraction_id, latitude, longitude)
SELECT NOW(6), NOW(6), a.id, v.latitude, v.longitude
FROM (
         SELECT '범퍼카' AS name, 37.2923658 AS latitude, 127.1995663 AS longitude
         UNION ALL SELECT '우주전투기', 37.2918125, 127.2011406  -- KAN-98 보정값
         UNION ALL SELECT '아마존 익스프레스', 37.2912666, 127.2057353
         UNION ALL SELECT '모니모 러쉬', 37.29, 127.2025  -- 소수점 4자리 반올림값, 재측정 필요
         UNION ALL SELECT '썬더폴스', 37.2936625, 127.1985781  -- KAN-98 보정값
         UNION ALL SELECT '콜럼버스 대탐험', 37.2945, 127.19957
         UNION ALL SELECT '더블 락스핀', 37.29299, 127.20199
         UNION ALL SELECT '렛츠 트위스트', 37.29272, 127.20084
         UNION ALL SELECT '롤링 엑스 트레인', 37.29293, 127.20141
         UNION ALL SELECT '허리케인', 37.29397, 127.20161
     ) v
         JOIN attractions a
              ON a.name = v.name
                  AND a.deleted_at IS NULL
    ON DUPLICATE KEY UPDATE
                         latitude   = v.latitude,
                         longitude  = v.longitude,
                         updated_at = NOW(6);

-- 2. 어트랙션 간 도보 이동 구간 90건 (네이버 지도 도보 길찾기 측정값, 방향별 1행)
INSERT INTO attraction_paths (created_at, updated_at, from_attraction_id, to_attraction_id, distance_m, move_minutes)
SELECT NOW(6), NOW(6), fa.id, ta.id, v.distance_m, v.move_minutes
FROM (
         -- 범퍼카 출발
         SELECT '범퍼카' AS from_name, '우주전투기' AS to_name, 322 AS distance_m, 5 AS move_minutes
         UNION ALL SELECT '범퍼카', '아마존 익스프레스', 641, 11
         UNION ALL SELECT '범퍼카', '모니모 러쉬', 632, 11
         UNION ALL SELECT '범퍼카', '썬더폴스', 141, 3
         UNION ALL SELECT '범퍼카', '콜럼버스 대탐험', 495, 12
         UNION ALL SELECT '범퍼카', '더블 락스핀', 493, 11
         UNION ALL SELECT '범퍼카', '렛츠 트위스트', 576, 12
         UNION ALL SELECT '범퍼카', '롤링 엑스 트레인', 508, 11
         UNION ALL SELECT '범퍼카', '허리케인', 342, 9
         -- 우주전투기 출발
         UNION ALL SELECT '우주전투기', '범퍼카', 322, 5
         UNION ALL SELECT '우주전투기', '아마존 익스프레스', 433, 10
         UNION ALL SELECT '우주전투기', '모니모 러쉬', 424, 9
         UNION ALL SELECT '우주전투기', '썬더폴스', 452, 8
         UNION ALL SELECT '우주전투기', '콜럼버스 대탐험', 648, 13
         UNION ALL SELECT '우주전투기', '더블 락스핀', 301, 7
         UNION ALL SELECT '우주전투기', '렛츠 트위스트', 273, 6
         UNION ALL SELECT '우주전투기', '롤링 엑스 트레인', 265, 6
         UNION ALL SELECT '우주전투기', '허리케인', 431, 9
         -- 아마존 익스프레스 출발
         UNION ALL SELECT '아마존 익스프레스', '범퍼카', 641, 12
         UNION ALL SELECT '아마존 익스프레스', '우주전투기', 433, 10
         UNION ALL SELECT '아마존 익스프레스', '모니모 러쉬', 217, 3
         UNION ALL SELECT '아마존 익스프레스', '썬더폴스', 808, 16
         UNION ALL SELECT '아마존 익스프레스', '콜럼버스 대탐험', 1100, 24
         UNION ALL SELECT '아마존 익스프레스', '더블 락스핀', 697, 17
         UNION ALL SELECT '아마존 익스프레스', '렛츠 트위스트', 669, 16
         UNION ALL SELECT '아마존 익스프레스', '롤링 엑스 트레인', 661, 16
         UNION ALL SELECT '아마존 익스프레스', '허리케인', 827, 19
         -- 모니모 러쉬 출발
         UNION ALL SELECT '모니모 러쉬', '범퍼카', 632, 12
         UNION ALL SELECT '모니모 러쉬', '우주전투기', 424, 10
         UNION ALL SELECT '모니모 러쉬', '아마존 익스프레스', 217, 3
         UNION ALL SELECT '모니모 러쉬', '썬더폴스', 799, 16
         UNION ALL SELECT '모니모 러쉬', '콜럼버스 대탐험', 1100, 24
         UNION ALL SELECT '모니모 러쉬', '더블 락스핀', 688, 16
         UNION ALL SELECT '모니모 러쉬', '렛츠 트위스트', 660, 16
         UNION ALL SELECT '모니모 러쉬', '롤링 엑스 트레인', 653, 16
         UNION ALL SELECT '모니모 러쉬', '허리케인', 818, 19
         -- 썬더폴스 출발
         UNION ALL SELECT '썬더폴스', '범퍼카', 141, 3
         UNION ALL SELECT '썬더폴스', '우주전투기', 452, 8
         UNION ALL SELECT '썬더폴스', '아마존 익스프레스', 808, 14
         UNION ALL SELECT '썬더폴스', '모니모 러쉬', 799, 14
         UNION ALL SELECT '썬더폴스', '콜럼버스 대탐험', 424, 9
         UNION ALL SELECT '썬더폴스', '더블 락스핀', 421, 8
         UNION ALL SELECT '썬더폴스', '렛츠 트위스트', 505, 9
         UNION ALL SELECT '썬더폴스', '롤링 엑스 트레인', 436, 8
         UNION ALL SELECT '썬더폴스', '허리케인', 271, 6
         -- 콜럼버스 대탐험 출발
         UNION ALL SELECT '콜럼버스 대탐험', '범퍼카', 495, 9
         UNION ALL SELECT '콜럼버스 대탐험', '우주전투기', 648, 11
         UNION ALL SELECT '콜럼버스 대탐험', '아마존 익스프레스', 1100, 21
         UNION ALL SELECT '콜럼버스 대탐험', '모니모 러쉬', 1100, 21
         UNION ALL SELECT '콜럼버스 대탐험', '썬더폴스', 424, 7
         UNION ALL SELECT '콜럼버스 대탐험', '더블 락스핀', 368, 6
         UNION ALL SELECT '콜럼버스 대탐험', '렛츠 트위스트', 451, 7
         UNION ALL SELECT '콜럼버스 대탐험', '롤링 엑스 트레인', 383, 6
         UNION ALL SELECT '콜럼버스 대탐험', '허리케인', 218, 4
         -- 더블 락스핀 출발
         UNION ALL SELECT '더블 락스핀', '범퍼카', 493, 9
         UNION ALL SELECT '더블 락스핀', '우주전투기', 301, 5
         UNION ALL SELECT '더블 락스핀', '아마존 익스프레스', 697, 14
         UNION ALL SELECT '더블 락스핀', '모니모 러쉬', 688, 14
         UNION ALL SELECT '더블 락스핀', '썬더폴스', 421, 7
         UNION ALL SELECT '더블 락스핀', '콜럼버스 대탐험', 368, 6
         UNION ALL SELECT '더블 락스핀', '렛츠 트위스트', 104, 1
         UNION ALL SELECT '더블 락스핀', '롤링 엑스 트레인', 48, 1
         UNION ALL SELECT '더블 락스핀', '허리케인', 150, 2
         -- 렛츠 트위스트 출발
         UNION ALL SELECT '렛츠 트위스트', '범퍼카', 576, 11
         UNION ALL SELECT '렛츠 트위스트', '우주전투기', 273, 5
         UNION ALL SELECT '렛츠 트위스트', '아마존 익스프레스', 669, 14
         UNION ALL SELECT '렛츠 트위스트', '모니모 러쉬', 660, 14
         UNION ALL SELECT '렛츠 트위스트', '썬더폴스', 505, 9
         UNION ALL SELECT '렛츠 트위스트', '콜럼버스 대탐험', 451, 8
         UNION ALL SELECT '렛츠 트위스트', '더블 락스핀', 104, 1
         UNION ALL SELECT '렛츠 트위스트', '롤링 엑스 트레인', 68, 1
         UNION ALL SELECT '렛츠 트위스트', '허리케인', 234, 4
         -- 롤링 엑스 트레인 출발
         UNION ALL SELECT '롤링 엑스 트레인', '범퍼카', 508, 9
         UNION ALL SELECT '롤링 엑스 트레인', '우주전투기', 265, 5
         UNION ALL SELECT '롤링 엑스 트레인', '아마존 익스프레스', 661, 14
         UNION ALL SELECT '롤링 엑스 트레인', '모니모 러쉬', 653, 14
         UNION ALL SELECT '롤링 엑스 트레인', '썬더폴스', 436, 7
         UNION ALL SELECT '롤링 엑스 트레인', '콜럼버스 대탐험', 383, 7
         UNION ALL SELECT '롤링 엑스 트레인', '더블 락스핀', 48, 1
         UNION ALL SELECT '롤링 엑스 트레인', '렛츠 트위스트', 68, 1
         UNION ALL SELECT '롤링 엑스 트레인', '허리케인', 165, 3
         -- 허리케인 출발
         UNION ALL SELECT '허리케인', '범퍼카', 342, 6
         UNION ALL SELECT '허리케인', '우주전투기', 431, 7
         UNION ALL SELECT '허리케인', '아마존 익스프레스', 827, 16
         UNION ALL SELECT '허리케인', '모니모 러쉬', 818, 16
         UNION ALL SELECT '허리케인', '썬더폴스', 271, 4
         UNION ALL SELECT '허리케인', '콜럼버스 대탐험', 218, 4
         UNION ALL SELECT '허리케인', '더블 락스핀', 150, 2
         UNION ALL SELECT '허리케인', '렛츠 트위스트', 234, 3
         UNION ALL SELECT '허리케인', '롤링 엑스 트레인', 165, 2
     ) v
         JOIN attractions fa
              ON fa.name = v.from_name
                  AND fa.deleted_at IS NULL
         JOIN attractions ta
              ON ta.name = v.to_name
                  AND ta.deleted_at IS NULL
    ON DUPLICATE KEY UPDATE
                         distance_m   = v.distance_m,
                         move_minutes = v.move_minutes,
                         updated_at   = NOW(6);