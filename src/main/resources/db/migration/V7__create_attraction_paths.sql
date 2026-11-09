-- V7__create_attraction_paths.sql
-- KAN-136 어트랙션 간 도보 이동 구간 (docs/sql/kan-97 1단계와 같은 구조)
-- 한 행 = 출발 → 도착. 방향마다 실측값이 다르므로 방향별로 저장한다.
-- boolean은 V1 컨벤션(하이버네이트 매핑)에 맞춰 bit(1)로 쓴다.
CREATE TABLE `attraction_paths` (
                                    `id` bigint NOT NULL AUTO_INCREMENT,
                                    `created_at` datetime(6) NOT NULL,
                                    `updated_at` datetime(6) NOT NULL,
                                    `from_attraction_id` bigint NOT NULL,
                                    `to_attraction_id` bigint NOT NULL,
                                    `distance_m` int NOT NULL,
                                    `move_minutes` int NOT NULL,
                                    `is_estimated` bit(1) NOT NULL DEFAULT b'0',
                                    `is_active` bit(1) NOT NULL DEFAULT b'1',
                                    PRIMARY KEY (`id`),
                                    CONSTRAINT `uk_attraction_paths_from_to` UNIQUE (`from_attraction_id`, `to_attraction_id`),
                                    CONSTRAINT `fk_attraction_paths_from` FOREIGN KEY (`from_attraction_id`) REFERENCES `attractions` (`id`),
                                    CONSTRAINT `fk_attraction_paths_to` FOREIGN KEY (`to_attraction_id`) REFERENCES `attractions` (`id`),
                                    CONSTRAINT `ck_attraction_paths_not_self` CHECK (`from_attraction_id` <> `to_attraction_id`),
                                    CONSTRAINT `ck_attraction_paths_distance` CHECK (`distance_m` > 0),
                                    CONSTRAINT `ck_attraction_paths_minutes` CHECK (`move_minutes` > 0)
);