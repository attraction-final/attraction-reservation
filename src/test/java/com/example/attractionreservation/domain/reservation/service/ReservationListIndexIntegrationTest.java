package com.example.attractionreservation.domain.reservation.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.reservation.dto.SearchReservationCondition;
import com.example.attractionreservation.domain.reservation.dto.SearchReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.util.KstTime;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// KAN-130: 인덱스 변경 전후의 SQL·바인딩 값·실행 계획을 수집하는 관찰용 테스트.
// 응답 정확성은 검증하지만 환경에 따라 달라지는 시간이나 특정 인덱스 선택을 합격 기준으로 두지 않는다.
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QuerydslConfig.class, ReservationService.class, SimpleMeterRegistry.class,
        ReservationListIndexIntegrationTest.MySqlConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("performance")
class ReservationListIndexIntegrationTest {

    private static final int USER_COUNT = 200;
    private static final int SLOT_COUNT = 500;
    private static final int DAY_COUNT = 10;
    private static final int SLOTS_PER_DAY = SLOT_COUNT / DAY_COUNT;
    private static final int PAGE_SIZE = 10;
    private static final int REPETITIONS = 5;
    private static final long TARGET_USER_ID = 101L;
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 1);
    private static final ReservationStatus[] STATUSES = ReservationStatus.values();

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingDataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MySQLContainer mysql;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private AttractionSlotService attractionSlotService;

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                    .withUrlParam("rewriteBatchedStatements", "true");
        }

        @Bean(destroyMethod = "close")
        RecordingDataSource dataSource(MySQLContainer mysql) {
            // 접속 대상은 이 테스트의 컨테이너로 고정한다. 로컬·공유 DB 설정은 사용하지 않는다.
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(mysql.getJdbcUrl());
            pool.setUsername(mysql.getUsername());
            pool.setPassword(mysql.getPassword());
            pool.setMaximumPoolSize(4);
            return new RecordingDataSource(pool);
        }
    }

    @Test
    @DisplayName("내 예약 목록 실행 계획과 인덱스 크기·예약 쓰기 비용을 수집한다")
    void findReservationAll_recordsIndexBaselineAndExecutionPlans() throws Exception {
        // given
        Path output = Path.of("build", "reports", "reservation-list-index",
                "run-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
        Files.createDirectories(output);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Long.class)).isZero();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seedData());
        // 대량 INSERT를 커밋한 뒤 통계를 갱신한다. 측정 중에는 데이터·인덱스를 변경하지 않는다.
        jdbc.queryForList("ANALYZE TABLE reservations, attraction_slots, attractions");
        recordEnvironment(output);

        // when
        recordScenario(output, "user-only", null, SLOT_COUNT);
        recordScenario(output, "user-reserved", ReservationStatus.RESERVED, SLOT_COUNT / STATUSES.length);
        // 원본 예약 테이블은 유지하고, 동일 데이터의 복제 테이블에서 인덱스 유지 비용을 비교한다.
        new ReservationIndexMaintenanceMeasurement(jdbc, transactionManager, mysql)
                .record(output.resolve("maintenance"), REPETITIONS);

        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Long.class))
                .isEqualTo((long) USER_COUNT * SLOT_COUNT);
        assertThat(Files.exists(output.resolve("user-only-content-analyze-5.txt"))).isTrue();
        assertThat(Files.exists(output.resolve("user-reserved-count-analyze-5.txt"))).isTrue();
        System.out.printf(Locale.ROOT,
                "event=RESERVATION_INDEX_BASELINE_RECORDED count=%d userCount=%d reservationsPerUser=%d "
                        + "repetitions=%d outputDirectory=\"%s\"%n",
                USER_COUNT * SLOT_COUNT, USER_COUNT, SLOT_COUNT, REPETITIONS, output.toAbsolutePath());
    }

    private void recordScenario(Path output, String scenario, ReservationStatus status, long total) throws Exception {
        PageRequest pageable = PageRequest.of(0, PAGE_SIZE,
                Sort.by(Sort.Order.desc("status"), Sort.Order.desc("id")));
        SearchReservationCondition condition = new SearchReservationCondition(status, null);
        List<RecordedSql> statements;
        Page<SearchReservationResponse> page;
        dataSource.startRecording();
        try {
            page = reservationService.findReservationAll(TARGET_USER_ID, pageable, condition);
        } finally {
            statements = dataSource.stopRecording();
        }

        // 변경된 SELECT 구조도 감지한다. 추측한 SQL을 별도로 만들어 측정하지 않는다.
        assertThat(statements).as("실제 Service의 목록·count SELECT").hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(total);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(PAGE_SIZE);
        assertThat(page.getNumberOfElements()).isEqualTo(PAGE_SIZE);
        assertThat(page.getTotalPages()).isEqualTo((int) ((total + PAGE_SIZE - 1) / PAGE_SIZE));
        assertThat(page.hasNext()).isTrue();

        // 소유자·정렬은 fixture의 DB 기준 결과와 비교한다. enum의 DB 정렬 순서를 Java ordinal로 추측하지 않는다.
        String predicate = status == null ? "" : " AND reservation_status = 'RESERVED'";
        List<Long> expectedIds = jdbc.queryForList(
                "SELECT id FROM reservations WHERE user_id = ?" + predicate
                        + " ORDER BY reservation_status DESC, id DESC LIMIT 10", Long.class, TARGET_USER_ID);
        assertThat(page.getContent()).extracting(SearchReservationResponse::reservationId)
                .containsExactlyElementsOf(expectedIds);
        for (SearchReservationResponse item : page) {
            assertThat(jdbc.queryForObject("SELECT user_id FROM reservations WHERE id = ?",
                    Long.class, item.reservationId())).isEqualTo(TARGET_USER_ID);
            assertThat(item.attractionName()).startsWith("KAN-130 시설 ");
            assertThat(item.ticketId()).isNotNull();
            assertThat(item.startsAt()).isBefore(item.endsAt());
            if (status != null) {
                assertThat(item.status()).isEqualTo(status);
            }
        }

        for (RecordedSql statement : statements) {
            String kind = statement.sql().toLowerCase(Locale.ROOT).contains("count(") ? "count" : "content";
            String prefix = scenario + "-" + kind;
            write(output.resolve(prefix + "-sql.txt"), statement.describe());
            write(output.resolve(prefix + "-explain.txt"), explain(statement, "EXPLAIN "));
            // EXPLAIN ANALYZE는 실제 쿼리를 실행한다. 1회 워밍업 후 5회 결과를 각각 보존한다.
            write(output.resolve(prefix + "-warmup.txt"), explain(statement, "EXPLAIN ANALYZE "));
            for (int iteration = 1; iteration <= REPETITIONS; iteration++) {
                String plan = explain(statement, "EXPLAIN ANALYZE ");
                assertThat(plan).contains("actual time=");
                write(output.resolve(prefix + "-analyze-" + iteration + ".txt"), plan);
                System.out.printf("event=RESERVATION_INDEX_PLAN_RECORDED scenario=%s queryType=%s "
                                + "iteration=%d plan=\"%s\"%n",
                        scenario, kind, iteration, escape(plan));
            }
        }
    }

    private void recordEnvironment(Path output) throws IOException {
        String metadata = "issue=KAN-130\nusers=" + USER_COUNT + "\nreservations=" + USER_COUNT * SLOT_COUNT
                + "\nreservationsPerUser=" + SLOT_COUNT + "\nslots=" + SLOT_COUNT
                + "\ndays=" + DAY_COUNT + "\nstatuses=" + List.of(STATUSES)
                + "\nstatusDistribution=25% each per user\ntargetUserId=" + TARGET_USER_ID
                + "\npage=0\nsize=" + PAGE_SIZE + "\nsort=status DESC,id DESC\nrepetitions=" + REPETITIONS
                + "\ncacheMode=warm; one EXPLAIN ANALYZE warmup before each query's repetitions"
                + "\nscope=synthetic SQL/index experiment; not production distribution or HTTP load test\n";
        metadata += jdbc.queryForList("SELECT VERSION() AS mysqlVersion, DATABASE() AS databaseName, "
                + "@@transaction_isolation AS isolationLevel, @@optimizer_switch AS optimizerSwitch") + "\n";
        write(output.resolve("environment.txt"), metadata);
        write(output.resolve("indexes-reservations.txt"), jdbc.queryForList("SHOW INDEX FROM reservations").toString());
        write(output.resolve("indexes-slots.txt"), jdbc.queryForList("SHOW INDEX FROM attraction_slots").toString());
        write(output.resolve("schema-reservations.txt"), jdbc.queryForList("SHOW CREATE TABLE reservations").toString());
        write(output.resolve("schema-slots.txt"), jdbc.queryForList("SHOW CREATE TABLE attraction_slots").toString());
        write(output.resolve("data-distribution.txt"), jdbc.queryForList(
                "SELECT reservation_status, COUNT(*) AS count FROM reservations GROUP BY reservation_status")
                + "\n" + jdbc.queryForList("SELECT user_id, reservation_status, COUNT(*) AS count "
                + "FROM reservations WHERE user_id = " + TARGET_USER_ID + " GROUP BY user_id, reservation_status"));
        write(output.resolve("flyway-history.txt"), jdbc.queryForList(
                "SELECT version, description, checksum, success FROM flyway_schema_history ORDER BY installed_rank").toString());
    }

    private String explain(RecordedSql statement, String prefix) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement prepared = connection.prepareStatement(prefix + statement.sql())) {
            // 실제 JDBC setter와 인자를 그대로 재사용한다. SQL에 값을 문자열 치환하지 않는다.
            for (Binding binding : statement.bindings()) {
                invoke(binding.method(), prepared, binding.arguments());
            }
            try (ResultSet rows = prepared.executeQuery()) {
                ResultSetMetaData metadata = rows.getMetaData();
                StringBuilder result = new StringBuilder();
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    result.append(metadata.getColumnLabel(column)).append('\t');
                }
                result.append('\n');
                while (rows.next()) {
                    for (int column = 1; column <= metadata.getColumnCount(); column++) {
                        result.append(rows.getString(column)).append('\t');
                    }
                    result.append('\n');
                }
                return result.toString();
            }
        }
    }

    private void seedData() {
        Timestamp createdAt = Timestamp.from(FIRST_DAY.atStartOfDay(KstTime.ZONE).toInstant());
        List<Object[]> users = new ArrayList<>();
        List<Object[]> orders = new ArrayList<>();
        List<Object[]> items = new ArrayList<>();
        List<Object[]> tickets = new ArrayList<>();
        for (int user = 1; user <= USER_COUNT; user++) {
            users.add(new Object[]{user, createdAt, createdAt, "kan130-" + user + "@example.test",
                    "측정회원" + user, "not-a-login-password", "LOCAL", "USER", "ACTIVE"});
            for (int day = 0; day < DAY_COUNT; day++) {
                int ticketId = (user - 1) * DAY_COUNT + day + 1;
                LocalDate date = FIRST_DAY.plusDays(day);
                orders.add(new Object[]{ticketId, createdAt, createdAt, "COMPLETED", 10000, date, user});
                items.add(new Object[]{ticketId, 10000, 1, ticketId, 1});
                tickets.add(new Object[]{ticketId, createdAt, createdAt, "KAN130-T-" + ticketId,
                        Timestamp.from(date.atTime(10, 0).atZone(KstTime.ZONE).toInstant()),
                        "측정용 종일권", "IN_USE", "ALL_DAY", date, ticketId});
            }
        }
        batch("INSERT INTO users (id,created_at,updated_at,email,nickname,password,provider,role,status) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", users);
        jdbc.update("INSERT INTO ticket_products (id,created_at,updated_at,description,name,price,status,stock,type,version) "
                + "VALUES (1,?,?, '인덱스 측정용','측정용 종일권',10000,'ON_SALE',1000000,'ALL_DAY',0)", createdAt, createdAt);
        batch("INSERT INTO orders (id,created_at,updated_at,status,total_amount,visit_date,user_id) VALUES (?,?,?,?,?,?,?)", orders);
        batch("INSERT INTO order_items (id,price,quantity,order_id,ticket_product_id) VALUES (?,?,?,?,?)", items);
        batch("INSERT INTO tickets (id,created_at,updated_at,code,entered_at,name,status,type,valid_date,order_item_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)", tickets);

        List<Object[]> attractions = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            attractions.add(new Object[]{index, createdAt, createdAt, "실행 계획 측정용", 10, 5,
                    "A구역", "KAN-130 시설 " + index, "OPERATING"});
        }
        batch("INSERT INTO attractions (id,created_at,updated_at,description,duration_minutes,entrance_move_minutes,"
                + "location,name,operation_status) VALUES (?,?,?,?,?,?,?,?,?)", attractions);
        List<Object[]> slots = new ArrayList<>();
        List<Object[]> reservations = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int day = slot / SLOTS_PER_DAY;
            LocalDateTime startsAt = FIRST_DAY.plusDays(day).atTime(10, 0).plusMinutes(slot % SLOTS_PER_DAY * 10L);
            // 취소 외 상태는 기존 슬롯 인원을 유지하는 모델에 맞춰 150명으로 구성한다.
            slots.add(new Object[]{slot + 1, createdAt, createdAt, false, USER_COUNT,
                    startsAt.plusMinutes(10), USER_COUNT * 3 / 4, startsAt, "OPEN", slot % 10 + 1});
            for (int user = 1; user <= USER_COUNT; user++) {
                long id = (long) slot * USER_COUNT + user;
                ReservationStatus status = STATUSES[(user + slot) % STATUSES.length];
                boolean cancelled = status == ReservationStatus.CANCELLED;
                reservations.add(new Object[]{id, createdAt, createdAt, cancelled ? "USER_REQUEST" : null,
                        cancelled ? createdAt : null, "KAN130-R-" + id, status.name(), slot + 1,
                        (user - 1) * DAY_COUNT + day + 1, user});
            }
        }
        batch("INSERT INTO attraction_slots (id,created_at,updated_at,auto_closed,capacity,ends_at,reserved_count,"
                + "starts_at,status,attraction_id) VALUES (?,?,?,?,?,?,?,?,?,?)", slots);
        // 조회용 합성 이력이다. 회원별 각 슬롯에 최대 1건이며 Generated Column에는 직접 쓰지 않는다.
        batch("INSERT INTO reservations (id,created_at,updated_at,cancel_reason,cancelled_at,reservation_number,"
                + "reservation_status,attraction_slot_id,ticket_id,user_id) VALUES (?,?,?,?,?,?,?,?,?,?)", reservations);
    }

    private void batch(String sql, List<Object[]> rows) {
        jdbc.batchUpdate(sql, rows, 1000, (statement, values) -> {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
        });
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static Object invoke(Method method, Object target, Object[] arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private record Binding(Method method, Object[] arguments) {
        String describe() {
            return method.getName() + " " + java.util.Arrays.toString(arguments);
        }
    }

    private record RecordedSql(String sql, List<Binding> bindings) {
        String describe() {
            StringBuilder result = new StringBuilder(sql).append("\n\nJDBC bindings:\n");
            bindings.forEach(binding -> result.append(binding.describe()).append('\n'));
            return result.toString();
        }
    }

    // JDBC 경계에서 SQL과 실제 setter 인자를 수집한다. 별도 라이브러리·프로덕션 설정을 추가하지 않는다.
    static class RecordingDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource pool;
        private final ThreadLocal<List<RecordedSql>> recording = new ThreadLocal<>();

        RecordingDataSource(HikariDataSource pool) {
            super(pool);
            this.pool = pool;
        }

        void startRecording() {
            if (recording.get() != null) {
                throw new IllegalStateException("SQL 수집을 중첩 실행할 수 없습니다.");
            }
            recording.set(new ArrayList<>());
        }

        List<RecordedSql> stopRecording() {
            try {
                return List.copyOf(recording.get());
            } finally {
                recording.remove();
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(pool.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(pool.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        Object result = invoke(method, connection, arguments);
                        if (recording.get() != null && method.getName().equals("prepareStatement")
                                && arguments[0] instanceof String sql && result instanceof PreparedStatement statement) {
                            return wrapStatement(statement, sql);
                        }
                        return result;
                    });
        }

        private PreparedStatement wrapStatement(PreparedStatement statement, String sql) {
            Map<Integer, Binding> bindings = new TreeMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("clearParameters")) {
                            bindings.clear();
                        } else if (method.getName().startsWith("set") && arguments != null
                                && arguments.length >= 2 && arguments[0] instanceof Integer index) {
                            bindings.put(index, new Binding(method, arguments.clone()));
                        }
                        if (recording.get() != null && (method.getName().equals("executeQuery")
                                || method.getName().equals("execute"))
                                && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")) {
                            recording.get().add(new RecordedSql(sql, List.copyOf(bindings.values())));
                        }
                        return invoke(method, statement, arguments);
                    });
        }

        @Override
        public void close() {
            pool.close();
        }
    }
}
