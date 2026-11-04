package com.example.attractionreservation.global.db;

import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 마이그레이션 SQL(db/migration)이 엔티티 매핑과 일치하는지 운영과 같은 MySQL 버전에서 검증한다.
// 다른 테스트는 엔티티로 스키마를 만들므로(create-drop), 엔티티만 바꾸고 마이그레이션을 빠뜨리면 이 테스트만 실패한다.
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
