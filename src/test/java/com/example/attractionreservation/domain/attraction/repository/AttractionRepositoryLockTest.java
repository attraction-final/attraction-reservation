package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 어트랙션 잠금 쿼리를 운영과 같은 MySQL 8.4에서 검증한다. (KAN-144)
// H2는 읽기 전용 트랜잭션의 FOR UPDATE를 거부하지 않아서, findAllActiveIds에 잠금이 잘못 붙은 문제를 잡지 못했다.
// (메서드를 추가하면서 바로 위의 @Lock 아래에 끼워 넣어 잠금이 옮겨 붙었다 → 슬롯 자동 생성이 매번 실패)
// 같은 실수로 findByIdForUpdate의 잠금이 빠지지 않도록 잠금이 걸리는지도 함께 확인한다.
// 트랜잭션의 읽기 전용 여부와 커밋 시점을 직접 다루기 위해 테스트 트랜잭션을 쓰지 않는다. (데이터는 @AfterEach에서 지운다)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionRepositoryLockTest.MySqlConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttractionRepositoryLockTest {

    // 운영 RDS와 같은 버전
    private static final String MYSQL_IMAGE = "mysql:8.4";
    // MySQL: NOWAIT 로 잠긴 행을 잠그려 할 때의 오류 코드
    private static final int ER_LOCK_NOWAIT = 3572;

    @Autowired
    private AttractionRepository attractionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

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

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM attractions");
    }

    @Test
    @DisplayName("슬롯 자동 생성 대상 조회는 읽기 전용 트랜잭션에서 실행할 수 있다")
    void findAllActiveIds_ReadOnlyTransaction_Success() {
        // given
        long id = insertAttraction("회전목마");
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        // when
        // AttractionSlotGenerationService.findTargetAttractionIds()와 같은 조건 (@Transactional(readOnly = true))
        List<Long> ids = readOnly.execute(status -> attractionRepository.findAllActiveIds());

        // then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("잠금 조회한 어트랙션 행은 트랜잭션이 끝날 때까지 다른 연결이 잠글 수 없다")
    void findByIdForUpdate_HoldsRowLock() {
        // given
        long id = insertAttraction("회전목마");

        // when & then
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            attractionRepository.findByIdForUpdate(id);

            assertThatThrownBy(() -> lockFromAnotherConnection(id))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getErrorCode())
                    .isEqualTo(ER_LOCK_NOWAIT);
        });
    }

    @Test
    @DisplayName("슬롯 자동 생성 대상 조회는 어트랙션 행을 잠그지 않는다")
    void findAllActiveIds_DoesNotLockRows() throws Exception {
        // given
        long id = insertAttraction("회전목마");

        // when
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Boolean lockedByOther = tx.execute(status -> {
            attractionRepository.findAllActiveIds();
            try {
                lockFromAnotherConnection(id);
                return false;
            } catch (SQLException e) {
                return true;
            }
        });

        // then
        assertThat(lockedByOther).isFalse();
    }

    // 테스트 트랜잭션과 다른 연결에서 같은 행을 기다리지 않고 잠가 본다. 이미 잠겨 있으면 ER_LOCK_NOWAIT
    private void lockFromAnotherConnection(long attractionId) throws SQLException {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement statement = other.prepareStatement(
                    "SELECT id FROM attractions WHERE id = ? FOR UPDATE NOWAIT")) {
                statement.setLong(1, attractionId);
                statement.executeQuery().close();
            } finally {
                other.rollback();
            }
        }
    }

    private long insertAttraction(String name) {
        jdbcTemplate.update("""
                INSERT INTO attractions (created_at, updated_at, description, duration_minutes,
                    entrance_move_minutes, location, name, operation_status)
                VALUES (NOW(6), NOW(6), '설명', 5, 1, '위치', ?, 'OPERATING')
                """, name);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM attractions WHERE name = ? AND deleted_at IS NULL", Long.class, name);
    }
}
