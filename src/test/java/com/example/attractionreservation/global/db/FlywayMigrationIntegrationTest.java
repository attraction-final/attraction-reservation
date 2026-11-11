package com.example.attractionreservation.global.db;

import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 마이그레이션 SQL(db/migration)이 엔티티 매핑과 일치하는지 운영과 같은 MySQL 버전에서 검증한다.
// 다른 테스트는 엔티티로 스키마를 만들므로(create-drop), 테이블·컬럼을 빠뜨리거나 컬럼 타입이 다르면 이 테스트만 실패한다.
// validate는 컬럼 길이·NULL 여부·UNIQUE 제약·인덱스·외래 키를 비교하지 않는다. (docs/database/migration.md 2장)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QuerydslConfig.class, FlywayMigrationIntegrationTest.MySqlConfig.class})
class FlywayMigrationIntegrationTest {

    // 운영 RDS와 같은 버전
    private static final String MYSQL_IMAGE = "mysql:8.4";
    private static final String INITIAL_SCRIPT = "db/migration/V1__init.sql";
    private static final String BASELINE_SCHEMA = "baseline_check";

    @Autowired
    private Flyway flyway;

    @Autowired
    private FlywayProperties flywayProperties;

    @Autowired
    private MySQLContainer mysql;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            return new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE));
        }
    }

    @Test
    @DisplayName("빈 DB에 마이그레이션을 모두 적용하면 엔티티 매핑 검증(ddl-auto=validate)을 통과한다")
    void migrate_EmptyDatabase_PassesEntityValidation() {
        // given
        // 컨텍스트가 뜨면서 Flyway가 빈 DB에 모든 마이그레이션을 적용했고,
        // 그 뒤 Hibernate가 ddl-auto=validate로 엔티티와 스키마를 비교했다. (불일치하면 컨텍스트가 뜨지 않는다)

        // when
        MigrationInfo[] applied = flyway.info().applied();

        // then
        assertSoftly(softly -> {
            softly.assertThat(flyway.info().pending()).isEmpty();
            softly.assertThat(applied).isNotEmpty();
            softly.assertThat(applied).allMatch(info -> info.getState().isApplied() && !info.getState().isFailed());
            softly.assertThat(applied[0].getVersion()).isEqualTo(MigrationVersion.fromVersion("1"));
        });
    }

    @Test
    @DisplayName("Flyway 도입 전 V1 스키마가 있는 DB(운영)는 V1을 기준선으로 표시하고 V2부터 적용한다")
    void migrate_ExistingInitialSchema_BaselinesAndAppliesLaterVersions() throws Exception {
        // given
        // 운영처럼 V1 스키마만 있고 Flyway 이력 테이블은 없는 DB를 만든다.
        DriverManagerDataSource existingDatabase = createSchemaWithInitialScript();
        Flyway prodLikeFlyway = Flyway.configure()
                .dataSource(existingDatabase)
                .locations(flywayProperties.getLocations().toArray(String[]::new))
                .baselineOnMigrate(flywayProperties.isBaselineOnMigrate())
                .baselineVersion(flywayProperties.getBaselineVersion())
                .load();

        // when
        MigrateResult result = prodLikeFlyway.migrate();

        // then
        MigrationInfo[] applied = prodLikeFlyway.info().applied();
        assertSoftly(softly -> {
            softly.assertThat(result.success).isTrue();
            softly.assertThat(applied[0].getVersion()).isEqualTo(MigrationVersion.fromVersion("1"));
            softly.assertThat(applied[0].getType().isBaseline()).isTrue();
            softly.assertThat(result.migrations).extracting(output -> output.version).doesNotContain("1");
            softly.assertThat(prodLikeFlyway.info().pending()).isEmpty();
        });
        assertThat(Arrays.stream(applied).map(MigrationInfo::getVersion))
                .contains(MigrationVersion.fromVersion("2"));
    }

    // validate는 UNIQUE 제약을 비교하지 않으므로 V2의 uk_attraction_name_active는 실제 INSERT로 검증한다.
    @Test
    @DisplayName("삭제되지 않은 어트랙션과 같은 이름으로 추가하면 UNIQUE 제약 위반이 발생한다")
    void insertAttraction_DuplicateActiveName_ThrowsDuplicateKeyException() {
        // given
        insertAttraction("회전목마", null);

        // when & then
        assertThatThrownBy(() -> insertAttraction("회전목마", null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("같은 이름의 삭제된 어트랙션이 여러 건 있어도 그 이름으로 추가할 수 있다")
    void insertAttraction_SameNameDeletedExists_Success() {
        // given
        insertAttraction("회전목마", "2026-01-01 00:00:00");
        insertAttraction("회전목마", "2026-01-02 00:00:00");

        // when
        insertAttraction("회전목마", null);

        // then
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attractions WHERE name = ?", Integer.class, "회전목마");
        assertThat(count).isEqualTo(3);
    }

    // validate는 UNIQUE·CHECK 제약을 비교하지 않으므로 V6(attraction_locations)·V7(attraction_paths) 제약은 실제 INSERT로 검증한다.
    @Test
    @DisplayName("같은 출발·도착 어트랙션의 이동 구간을 두 번 넣으면 UNIQUE 제약 위반이 발생한다")
    void insertAttractionPath_DuplicateDirection_ThrowsDuplicateKeyException() {
        // given
        long from = insertAttractionAndGetId("범퍼카");
        long to = insertAttractionAndGetId("썬더폴스");
        insertAttractionPath(from, to, 141, 3);

        // when & then
        assertThatThrownBy(() -> insertAttractionPath(from, to, 141, 3))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("방향이 반대인 이동 구간은 별도 행으로 넣을 수 있다")
    void insertAttractionPath_ReverseDirection_Success() {
        // given
        long a = insertAttractionAndGetId("범퍼카");
        long b = insertAttractionAndGetId("아마존 익스프레스");
        insertAttractionPath(a, b, 641, 11);

        // when
        insertAttractionPath(b, a, 641, 12);

        // then
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attraction_paths", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    // MySQL의 CHECK 위반(오류 코드 3819, SQLState HY000)은 Spring이 DataIntegrityViolationException으로 번역하지 않고
    // UncategorizedSQLException으로 던진다. 그래서 예외 타입 대신 위반한 제약 이름으로 검증한다.
    @Test
    @DisplayName("출발과 도착이 같은 이동 구간은 CHECK 제약 위반이 발생한다")
    void insertAttractionPath_SameAttraction_ThrowsCheckConstraintViolation() {
        // given
        long a = insertAttractionAndGetId("범퍼카");

        // when & then
        assertThatThrownBy(() -> insertAttractionPath(a, a, 10, 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_attraction_paths_not_self");
    }

    @Test
    @DisplayName("한 어트랙션의 좌표를 두 번 넣으면 UNIQUE 제약 위반이 발생한다")
    void insertAttractionLocation_Duplicate_ThrowsDuplicateKeyException() {
        // given
        long a = insertAttractionAndGetId("범퍼카");
        insertAttractionLocation(a, 37.2923658, 127.1995663);

        // when & then
        assertThatThrownBy(() -> insertAttractionLocation(a, 37.2923658, 127.1995663))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // V10: KAN-142부터 쓰지 않는 추천 저장 테이블을 지웠다. (V8·V9가 바꾼 테이블도 여기서 함께 사라진다)
    @Test
    @DisplayName("추천 경로 저장 테이블은 마이그레이션 후 남아 있지 않다")
    void migrate_DropsRouteRecommendationTables() {
        // when
        Integer remaining = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('route_recommendations', 'route_recommendation_stops', 'route_recommendation_exclusions')
                """, Integer.class);

        // then
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("V12 적용 후 종일권과 오후권이 정해진 가격과 재고로 판매 중이다")
    void migrate_CreatesInitialTicketProducts() {
        // given
        // 컨텍스트 시작 시 빈 MySQL에 실제 migration 전체가 적용됐다.

        // when
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_products", Integer.class);

        // then
        assertThat(count).isEqualTo(2);
        assertInitialTicketProduct(TicketType.ALL_DAY);
        assertInitialTicketProduct(TicketType.AFTERNOON);
        assertThat(Arrays.stream(flyway.info().applied()).map(MigrationInfo::getVersion))
                .contains(MigrationVersion.fromVersion("12"));
    }

    @Test
    @DisplayName("초기 상품 SQL을 다시 실행해도 상품을 중복 등록하거나 기존 값을 변경하지 않는다")
    void rerunInitialTicketProductScript_PreservesExistingRows() {
        // given
        var before = jdbcTemplate.queryForList("SELECT * FROM ticket_products ORDER BY id");

        // when
        // Flyway의 버전 재실행이 아니라 SQL 자체의 중복 방지 조건을 검증한다.
        executeInitialTicketProductScript();
        executeInitialTicketProductScript();

        // then
        assertThat(jdbcTemplate.queryForList("SELECT * FROM ticket_products ORDER BY id"))
                .isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(TicketType.class)
    @DisplayName("같은 종류의 판매 중지 상품이 있으면 가격 재고 상태와 수정 이력을 보존한다")
    void seedTicketProducts_PreservesStoppedProduct(TicketType type) {
        // given
        jdbcTemplate.update("""
                UPDATE ticket_products
                SET name = '기존 상품', price = 42000, stock = 17, status = 'STOPPED',
                    version = 7, description = '관리자가 설정한 상품',
                    updated_at = '2026-01-01 00:00:00'
                WHERE type = ?
                """, type.name());
        var before = jdbcTemplate.queryForList("SELECT * FROM ticket_products ORDER BY id");

        // when
        executeInitialTicketProductScript();

        // then
        assertThat(jdbcTemplate.queryForList("SELECT * FROM ticket_products ORDER BY id"))
                .isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_products WHERE type = ?", Integer.class, type.name()))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(TicketType.class)
    @DisplayName("같은 종류의 삭제 상품만 있으면 삭제 이력을 유지하고 판매 상품을 새로 등록한다")
    void seedTicketProducts_AddsProductWhenOnlyDeletedProductExists(TicketType type) {
        // given
        jdbcTemplate.update("UPDATE ticket_products SET status = 'DELETED' WHERE type = ?", type.name());
        var deletedBefore = jdbcTemplate.queryForMap(
                "SELECT * FROM ticket_products WHERE type = ?", type.name());

        // when
        executeInitialTicketProductScript();

        // then
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_products", Integer.class))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT * FROM ticket_products WHERE type = ? AND status = 'DELETED'", type.name()))
                .isEqualTo(deletedBefore);
        assertInitialTicketProduct(type);
    }

    private void executeInitialTicketProductScript() {
        // JdbcTemplate의 트랜잭션 연결을 사용해 각 테스트의 변경이 종료 후 롤백되게 한다.
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            // Flyway와 동일하게 UTF-8로 읽는다. Windows 기본 charset에 맡기면 한글 값이 깨질 수 있다.
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration/V12__insert_initial_ticket_products.sql"),
                    StandardCharsets.UTF_8));
            return null;
        });
    }

    private void assertInitialTicketProduct(TicketType type) {
        var row = jdbcTemplate.queryForMap(
                "SELECT * FROM ticket_products WHERE type = ? AND status <> 'DELETED'", type.name());
        boolean allDay = type == TicketType.ALL_DAY;
        assertSoftly(softly -> {
            softly.assertThat(row.get("name")).isEqualTo(allDay ? "종일권" : "오후권");
            softly.assertThat(((Number) row.get("price")).intValue()).isEqualTo(allDay ? 50000 : 35000);
            softly.assertThat(((Number) row.get("stock")).intValue()).isEqualTo(1000);
            softly.assertThat(row.get("status")).isEqualTo("ON_SALE");
            softly.assertThat(((Number) row.get("version")).longValue()).isZero();
            softly.assertThat((String) row.get("description")).isNotBlank();
            softly.assertThat(row.get("created_at")).isNotNull();
            softly.assertThat(row.get("updated_at")).isNotNull();
        });
    }

    private long insertAttractionAndGetId(String name) {
        insertAttraction(name, null);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM attractions WHERE name = ? AND deleted_at IS NULL", Long.class, name);
    }

    private void insertAttractionPath(long fromId, long toId, int distanceM, int moveMinutes) {
        jdbcTemplate.update("""
                INSERT INTO attraction_paths (created_at, updated_at, from_attraction_id, to_attraction_id,
                    distance_m, move_minutes)
                VALUES (NOW(6), NOW(6), ?, ?, ?, ?)
                """, fromId, toId, distanceM, moveMinutes);
    }

    private void insertAttractionLocation(long attractionId, double latitude, double longitude) {
        jdbcTemplate.update("""
                INSERT INTO attraction_locations (created_at, updated_at, attraction_id, latitude, longitude)
                VALUES (NOW(6), NOW(6), ?, ?, ?)
                """, attractionId, latitude, longitude);
    }

    private void insertAttraction(String name, String deletedAt) {
        jdbcTemplate.update("""
                INSERT INTO attractions (created_at, updated_at, deleted_at, description,
                    duration_minutes, entrance_move_minutes, location, name, operation_status)
                VALUES (NOW(6), NOW(6), ?, '설명', 5, 1, '위치', ?, 'OPERATING')
                """, deletedAt, name);
    }

    // 컨테이너의 root 계정으로 별도 스키마를 만들고 V1 SQL만 실행한다. (Flyway 이력 없음)
    private DriverManagerDataSource createSchemaWithInitialScript() throws Exception {
        DriverManagerDataSource root = new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword());
        try (Connection connection = root.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + BASELINE_SCHEMA);
        }

        String schemaUrl = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + BASELINE_SCHEMA);
        DriverManagerDataSource existingDatabase = new DriverManagerDataSource(schemaUrl, "root", mysql.getPassword());
        try (Connection connection = existingDatabase.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(INITIAL_SCRIPT));
        }
        return existingDatabase;
    }
}
