-- 초기 스키마. 2026-11-05 운영 DB(MySQL 8.4)에서 mysqldump --no-data로 추출했다.
-- 운영 DB는 이 상태에서 Flyway 기준선(baseline 1)으로 표시되므로 운영에는 실행되지 않는다.
-- 빈 DB(로컬, 마이그레이션 검증 테스트)에서만 실행된다.
-- 이미 적용된 마이그레이션이므로 수정하지 않는다. 변경은 새 버전 파일로 추가한다. (docs/database/migration.md)
-- 외래 키가 참조하는 테이블을 먼저 만들도록 순서를 맞췄다.

CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `email` varchar(50) NOT NULL,
  `nickname` varchar(50) NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `provider` enum('GOOGLE','KAKAO','LOCAL') NOT NULL,
  `provider_user_id` varchar(255) DEFAULT NULL,
  `role` enum('ADMIN','USER') NOT NULL,
  `status` enum('ACTIVE','INACTIVE','SUSPENDED','WITHDRAWN') NOT NULL,
  `withdrawn_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`),
  UNIQUE KEY `uk_users_provider_user_id` (`provider`,`provider_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `attractions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `duration_minutes` int NOT NULL,
  `entrance_move_minutes` int NOT NULL,
  `location` varchar(50) NOT NULL,
  `name` varchar(50) NOT NULL,
  `operation_status` enum('DELETED','MAINTENANCE','OPERATING','SUSPENDED') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `attraction_slots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `auto_closed` bit(1) NOT NULL,
  `capacity` int NOT NULL,
  `ends_at` datetime(6) NOT NULL,
  `reserved_count` int NOT NULL,
  `starts_at` datetime(6) NOT NULL,
  `status` enum('CANCELLED','CLOSED','OPEN','SUSPENDED') NOT NULL,
  `attraction_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_attraction_slots_attraction_starts` (`attraction_id`,`starts_at`),
  CONSTRAINT `FK65hbj9g1x231xvnowtd8xsns5` FOREIGN KEY (`attraction_id`) REFERENCES `attractions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket_products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `description` varchar(200) NOT NULL,
  `name` varchar(80) NOT NULL,
  `price` int NOT NULL,
  `status` enum('DELETED','ENDED','ON_SALE','OUT_OF_STOCK','STOPPED') NOT NULL,
  `stock` int NOT NULL,
  `type` enum('AFTERNOON','ALL_DAY') NOT NULL,
  `version` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` enum('CANCELLED','COMPLETED','PARTIALLY_REFUNDED','PENDING','REFUNDED') NOT NULL,
  `total_amount` int NOT NULL,
  `visit_date` date NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK32ql8ubntj5uh44ph9659tiih` (`user_id`),
  CONSTRAINT `FK32ql8ubntj5uh44ph9659tiih` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `order_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `price` int NOT NULL,
  `quantity` int NOT NULL,
  `order_id` bigint NOT NULL,
  `ticket_product_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKbioxgbv59vetrxe0ejfubep1w` (`order_id`),
  KEY `FKe2j5kqddrl68rue08xaoq0sa` (`ticket_product_id`),
  CONSTRAINT `FKbioxgbv59vetrxe0ejfubep1w` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`),
  CONSTRAINT `FKe2j5kqddrl68rue08xaoq0sa` FOREIGN KEY (`ticket_product_id`) REFERENCES `ticket_products` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` int NOT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `pg_id` varchar(40) NOT NULL,
  `status` enum('CANCELLED','FAILED','PAID','PARTIALLY_REFUNDED','PENDING','REFUNDED') NOT NULL,
  `order_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKnqha4b344njmg10rxwm5lbpqc` (`pg_id`),
  UNIQUE KEY `UK8vo36cen604as7etdfwmyjsxt` (`order_id`),
  CONSTRAINT `FK81gagumt0r8y3rmudcgpbk42l` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `payment_compensations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `amount` bigint NOT NULL,
  `attempt_count` int NOT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `next_retry_at` datetime(6) NOT NULL,
  `pg_cancellation_id` varchar(100) DEFAULT NULL,
  `pg_id` varchar(40) NOT NULL,
  `pg_pending_since` datetime(6) DEFAULT NULL,
  `processed_at` datetime(6) DEFAULT NULL,
  `reason` varchar(100) NOT NULL,
  `status` enum('DEAD','DONE','FAILED','PG_PENDING','PROCESSING','REQUESTED') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKg8bgyqu7334ls8xunhp1qs3dv` (`pg_id`),
  KEY `idx_payment_compensations_status_retry` (`status`,`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `refunds` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `attempt_count` int NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `next_retry_at` datetime(6) NOT NULL,
  `pg_cancellation_id` varchar(100) DEFAULT NULL,
  `pg_pending_since` datetime(6) DEFAULT NULL,
  `reason` varchar(200) NOT NULL,
  `refund_amount` int NOT NULL,
  `requested_at` datetime(6) NOT NULL,
  `status` enum('COMPLETED','DEAD','FAILED','PG_PENDING','PROCESSING','REQUESTED') NOT NULL,
  `payment_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_refunds_status_retry` (`status`,`next_retry_at`),
  KEY `FKpt9ic0j1y6xwlej99wnynvnpy` (`payment_id`),
  CONSTRAINT `FKpt9ic0j1y6xwlej99wnynvnpy` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `refund_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `quantity` int NOT NULL,
  `order_item_id` bigint NOT NULL,
  `refund_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKt30eqahpu8nehh4y98ik6c4i2` (`order_item_id`),
  KEY `FKrmpg1ehoqwl6q0aql0679jpnd` (`refund_id`),
  CONSTRAINT `FKrmpg1ehoqwl6q0aql0679jpnd` FOREIGN KEY (`refund_id`) REFERENCES `refunds` (`id`),
  CONSTRAINT `FKt30eqahpu8nehh4y98ik6c4i2` FOREIGN KEY (`order_item_id`) REFERENCES `order_items` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tickets` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(50) NOT NULL,
  `end_time` datetime(6) DEFAULT NULL,
  `entered_at` datetime(6) DEFAULT NULL,
  `name` varchar(80) NOT NULL,
  `status` enum('EXPIRED','IN_USE','REFUNDED','UNUSED','USED') NOT NULL,
  `type` enum('AFTERNOON','ALL_DAY') NOT NULL,
  `valid_date` date NOT NULL,
  `order_item_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKe13ki8l4l6u599ociuljrqka9` (`code`),
  KEY `FK1avmygv76x52clnkdj625qr7h` (`order_item_id`),
  CONSTRAINT `FK1avmygv76x52clnkdj625qr7h` FOREIGN KEY (`order_item_id`) REFERENCES `order_items` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reservations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `active_reservation` int GENERATED ALWAYS AS ((case when (`reservation_status` = _utf8mb4'RESERVED') then 1 else NULL end)) VIRTUAL,
  `cancel_reason` enum('ATTRACTION_MAINTENANCE','OPERATIONAL_ISSUE','OTHER','SAFETY_CONCERN','USER_REQUEST','USER_WITHDRAWAL','WEATHER_CONDITION') DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `reservation_number` varchar(20) NOT NULL,
  `reservation_status` enum('CANCELLED','COMPLETED','NO_SHOW','RESERVED') NOT NULL,
  `attraction_slot_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKha0pvynkykm6kk26japu4r5rf` (`reservation_number`),
  UNIQUE KEY `uk_reservations_user_slot_active` (`user_id`,`attraction_slot_id`,`active_reservation`),
  KEY `FKcmoki4oisrprbwlrh3gw9vnh2` (`attraction_slot_id`),
  KEY `FK9bisghlqktclj5rm35ofbgnrh` (`ticket_id`),
  CONSTRAINT `FK9bisghlqktclj5rm35ofbgnrh` FOREIGN KEY (`ticket_id`) REFERENCES `tickets` (`id`),
  CONSTRAINT `FKb5g9io5h54iwl2inkno50ppln` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKcmoki4oisrprbwlrh3gw9vnh2` FOREIGN KEY (`attraction_slot_id`) REFERENCES `attraction_slots` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `route_recommendations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `end_at` datetime(6) NOT NULL,
  `estimated_end_at` datetime(6) NOT NULL,
  `result_type` enum('FULL','NONE','PARTIAL') NOT NULL,
  `start_at` datetime(6) NOT NULL,
  `start_location` enum('ENTRANCE') NOT NULL,
  `ticket_id` bigint NOT NULL,
  `total_duration_minutes` int NOT NULL,
  `total_move_minutes` int NOT NULL,
  `total_ride_minutes` int NOT NULL,
  `total_selected_attractions` int NOT NULL,
  `total_wait_minutes` int NOT NULL,
  `travel_time_basis` enum('ENTRANCE_FIXED') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `route_recommendation_exclusions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attraction_id` bigint NOT NULL,
  `attraction_name_snapshot` varchar(100) NOT NULL,
  `reason_code` enum('NOT_OPERATING','OUT_OF_TIME_RANGE','RESERVATION_CLOSED','SLOT_FULL','SLOT_NOT_OPEN','TIME_CONFLICT') NOT NULL,
  `reason_message` varchar(255) NOT NULL,
  `recommendation_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK9consoeta1o11y4koevmntyvi` (`recommendation_id`),
  CONSTRAINT `FK9consoeta1o11y4koevmntyvi` FOREIGN KEY (`recommendation_id`) REFERENCES `route_recommendations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `route_recommendation_stops` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `attraction_id` bigint NOT NULL,
  `attraction_name_snapshot` varchar(100) NOT NULL,
  `attraction_slot_id` bigint NOT NULL,
  `estimated_arrival_at` datetime(6) NOT NULL,
  `estimated_move_min` int NOT NULL,
  `estimated_ride_min` int NOT NULL,
  `estimated_wait_min` int NOT NULL,
  `sequence_number` int NOT NULL,
  `slot_end_at` datetime(6) NOT NULL,
  `slot_start_at` datetime(6) NOT NULL,
  `recommendation_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK9qfsnlm8uiv922m2c7bkn8ck6` (`recommendation_id`),
  CONSTRAINT `FK9qfsnlm8uiv922m2c7bkn8ck6` FOREIGN KEY (`recommendation_id`) REFERENCES `route_recommendations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `webhook_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `attempt_count` int NOT NULL,
  `event_type` varchar(50) NOT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `next_retry_at` datetime(6) DEFAULT NULL,
  `payload` text NOT NULL,
  `pg_id` varchar(40) DEFAULT NULL,
  `processed_at` datetime(6) DEFAULT NULL,
  `status` enum('DEAD','FAILED','IGNORED','PROCESSED','PROCESSING','RECEIVED') NOT NULL,
  `webhook_id` varchar(100) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKdo5484dx57aefrq5oud5ambln` (`webhook_id`),
  KEY `idx_webhook_events_pg_id` (`pg_id`),
  KEY `idx_webhook_events_status_retry` (`status`,`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
