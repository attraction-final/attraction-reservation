-- KAN-98 썬더폴스·우주전투기 좌표 오류 수정
-- 원인: KAN-97로 추가한 attraction_locations 중 2건이 실제 위치와 달라,
--       좌표로 계산한 직선거리가 실제 도보 거리보다 길게 나왔다. (물리적으로 불가능)
--   - 썬더폴스: 기존 좌표가 실제 위치에서 약 444m 벗어남 (범퍼카 ↔ 썬더폴스 도보 141m, 직선 약 382m)
--   - 우주전투기: 기존 좌표가 실제 위치에서 약 164m 벗어남 (우주전투기 ↔ 더블 락스핀 도보 301m, 직선 약 38m)
-- 수정 기준: 구글 지도 장소 핀(플러스 코드) 좌표
-- 선행 조건: docs/sql/kan-97-route-travel-data-migration.sql 적용 완료
-- 이미 머지된 kan-97 파일은 수정하지 않는다. (이미 실행한 DB에도 같은 결과가 나오도록 UPDATE로 정정)
-- 같은 값으로 덮어쓰는 UPDATE라 여러 번 실행해도 결과가 같다.

-- 1. 썬더폴스 좌표 수정
--    id 대신 이름으로 찾는다. (DB마다 어트랙션 id가 다를 수 있음)
UPDATE attraction_locations l
JOIN attractions a ON a.id = l.attraction_id
SET l.latitude  = 37.2936625,
    l.longitude = 127.1985781
WHERE a.name = '썬더폴스'
  AND a.deleted_at IS NULL;

-- 2. 우주전투기 좌표 수정
UPDATE attraction_locations l
JOIN attractions a ON a.id = l.attraction_id
SET l.latitude  = 37.2918125,
    l.longitude = 127.2011406
WHERE a.name = '우주전투기'
  AND a.deleted_at IS NULL;

-- 3. 적용 확인 1: 두 어트랙션의 좌표가 위 값으로 나와야 한다.
SELECT a.name, l.latitude, l.longitude
FROM attraction_locations l
JOIN attractions a ON a.id = l.attraction_id
WHERE a.name IN ('썬더폴스', '우주전투기');

-- 4. 적용 확인 2: 좌표로 계산한 직선거리가 도보 거리보다 긴 구간
--    아래 4쌍(양방향 8행)만 나오면 정상이다. (좌표가 어트랙션 중심 핀 기준이라 생기는 2~97m 오차)
--    아마존 익스프레스↔모니모 러쉬, 범퍼카↔썬더폴스, 더블 락스핀↔롤링 엑스 트레인, 더블 락스핀↔렛츠 트위스트
SELECT fa.name AS from_name,
       ta.name AS to_name,
       p.distance_m AS walk_m,
       ROUND(ST_Distance_Sphere(POINT(f.longitude, f.latitude), POINT(t.longitude, t.latitude))) AS straight_m
FROM attraction_paths p
JOIN attraction_locations f ON f.attraction_id = p.from_attraction_id
JOIN attraction_locations t ON t.attraction_id = p.to_attraction_id
JOIN attractions fa ON fa.id = p.from_attraction_id
JOIN attractions ta ON ta.id = p.to_attraction_id
HAVING straight_m > walk_m
ORDER BY straight_m - walk_m DESC;
