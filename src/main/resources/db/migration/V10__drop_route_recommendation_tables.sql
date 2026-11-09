-- V10__drop_route_recommendation_tables.sql
-- KAN-147 추천 경로 저장 테이블 삭제
-- KAN-142부터 추천 결과를 저장하지 않아 엔티티를 지웠고, 테이블은 이전 버전 앱을 위해 한 배포 동안 남겨 두었다.
-- KAN-142가 운영에 배포된 뒤(이전 버전 앱이 모두 내려간 뒤)에 적용해야 한다. (docs/database/migration.md 3장)
-- 외래 키로 route_recommendations를 참조하는 두 테이블을 같은 문장에서 함께 지운다.
DROP TABLE `route_recommendation_stops`, `route_recommendation_exclusions`, `route_recommendations`;
