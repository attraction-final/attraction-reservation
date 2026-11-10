-- V9__add_current_location_start_location.sql
-- KAN-140 추천 경로 출발 위치에 CURRENT_LOCATION(사용자 현재 위치)을 추가한다.
-- start_location은 MySQL enum이라, Java enum에만 값을 추가하면 저장 시 "Data truncated" 오류가 난다.
-- enum 끝에 값을 추가하는 변경이므로 기존 행(ENTRANCE)은 그대로 유지된다.
ALTER TABLE `route_recommendations`
    MODIFY COLUMN `start_location` enum('ENTRANCE','CURRENT_LOCATION') NOT NULL;
