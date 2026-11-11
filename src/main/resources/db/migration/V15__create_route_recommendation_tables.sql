-- V15__create_route_recommendation_tables.sql
-- KAN-163 현재 위치 기반 추천 결과 저장 테이블 (V10에서 삭제한 테이블을 새 구조로 다시 만든다)
-- 원본 좌표는 저장하지 않는다. 사유·결과 유형은 enum 추가 시 마이그레이션이 필요 없도록 varchar로 둔다.
CREATE TABLE `route_recommendations` (
                                         `id` bigint NOT NULL AUTO_INCREMENT,
                                         `created_at` datetime(6) NOT NULL,
                                         `updated_at` datetime(6) NOT NULL,
                                         `user_id` bigint NOT NULL,
                                         `ticket_id` bigint NOT NULL,
                                         `start_at` datetime(6) NOT NULL,
                                         `end_at` datetime(6) NOT NULL,
                                         `result_type` varchar(20) NOT NULL,
                                         `total_selected_attractions` int NOT NULL,
                                         `total_move_minutes` int NOT NULL,
                                         `total_wait_minutes` int NOT NULL,
                                         `total_ride_minutes` int NOT NULL,
                                         `total_duration_minutes` int NOT NULL,
                                         `estimated_end_at` datetime(6) NOT NULL,
                                         PRIMARY KEY (`id`),
                                         KEY `idx_route_recommendations_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `route_recommendation_stops` (
                                              `id` bigint NOT NULL AUTO_INCREMENT,
                                              `recommendation_id` bigint NOT NULL,
                                              `attraction_id` bigint NOT NULL,
                                              `attraction_slot_id` bigint NOT NULL,
                                              `sequence_number` int NOT NULL,
                                              `attraction_name_snapshot` varchar(100) NOT NULL,
                                              `estimated_move_min` int NOT NULL,
                                              `move_estimated` bit(1) NOT NULL,
                                              `estimated_wait_min` int NOT NULL,
                                              `estimated_ride_min` int NOT NULL,
                                              `estimated_arrival_at` datetime(6) NOT NULL,
                                              `slot_start_at` datetime(6) NOT NULL,
                                              `slot_end_at` datetime(6) NOT NULL,
                                              PRIMARY KEY (`id`),
                                              KEY `idx_route_recommendation_stops_recommendation_id` (`recommendation_id`),
                                              CONSTRAINT `fk_route_recommendation_stops_recommendation` FOREIGN KEY (`recommendation_id`) REFERENCES `route_recommendations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `route_recommendation_exclusions` (
                                                   `id` bigint NOT NULL AUTO_INCREMENT,
                                                   `recommendation_id` bigint NOT NULL,
                                                   `attraction_id` bigint NOT NULL,
                                                   `attraction_name_snapshot` varchar(100) NOT NULL,
                                                   `reason_code` varchar(50) NOT NULL,
                                                   `reason_message` varchar(255) NOT NULL,
                                                   PRIMARY KEY (`id`),
                                                   KEY `idx_route_recommendation_exclusions_recommendation_id` (`recommendation_id`),
                                                   CONSTRAINT `fk_route_recommendation_exclusions_recommendation` FOREIGN KEY (`recommendation_id`) REFERENCES `route_recommendations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;