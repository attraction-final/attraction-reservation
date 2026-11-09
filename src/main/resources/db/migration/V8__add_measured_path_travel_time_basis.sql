-- V8__add_measured_path_travel_time_basis.sql
-- KAN-137 추천 경로 이동 시간 기준에 MEASURED_PATH(실측 구간 기준)를 추가한다.
-- travel_time_basis는 MySQL enum이라, Java enum에만 값을 추가하면 저장 시 "Data truncated" 오류가 난다.
-- enum 끝에 값을 추가하는 변경이므로 기존 행(ENTRANCE_FIXED)은 그대로 유지된다.
ALTER TABLE `route_recommendations`
    MODIFY COLUMN `travel_time_basis` enum('ENTRANCE_FIXED','MEASURED_PATH') NOT NULL;
