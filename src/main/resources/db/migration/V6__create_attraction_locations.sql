-- V6__create_attraction_locations.sql
-- KAN-136 어트랙션 좌표 (WGS84). 추천 기능 전용이라 attractions에 컬럼을 추가하지 않고 별도 테이블로 둔다.
CREATE TABLE `attraction_locations` (
                                        `id` bigint NOT NULL AUTO_INCREMENT,
                                        `created_at` datetime(6) NOT NULL,
                                        `updated_at` datetime(6) NOT NULL,
                                        `attraction_id` bigint NOT NULL,
                                        `latitude` double NOT NULL,
                                        `longitude` double NOT NULL,
                                        PRIMARY KEY (`id`),
                                        CONSTRAINT `uk_attraction_locations_attraction` UNIQUE (`attraction_id`),
                                        CONSTRAINT `fk_attraction_locations_attraction` FOREIGN KEY (`attraction_id`) REFERENCES `attractions` (`id`),
                                        CONSTRAINT `ck_attraction_locations_latitude` CHECK (`latitude` BETWEEN -90 AND 90),
                                        CONSTRAINT `ck_attraction_locations_longitude` CHECK (`longitude` BETWEEN -180 AND 180)
);