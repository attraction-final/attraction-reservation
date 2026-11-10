package com.example.attractionreservation.domain.reservation.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 예약 목록의 인덱스 크기·쓰기 비용을 수집하는 공통 테스트 도우미다. 업무 Service는 아니다.
// 모든 DDL·DML은 해당 테스트의 MySQL 컨테이너에서만 수행한다.
final class ReservationIndexMaintenanceMeasurement {

    private static final int BATCH_SIZE = 1000;
    private static final String COLUMNS = "id,created_at,updated_at,cancel_reason,cancelled_at,"
            + "reservation_number,reservation_status,attraction_slot_id,ticket_id,user_id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcTemplate statisticsJdbc;
    private final String issue;
    private final String indexName;

    ReservationIndexMaintenanceMeasurement(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                          MySQLContainer mysql) {
        this(jdbc, manager, mysql, "KAN-130", "idx_reservations_user_status_id");
    }

    ReservationIndexMaintenanceMeasurement(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                          MySQLContainer mysql, String issue, String indexName) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.issue = issue;
        this.indexName = indexName;
        // mysql.innodb_index_stats 조회에만 컨테이너 root 계정을 사용한다. 접속 정보는 기록하지 않는다.
        this.statisticsJdbc = new JdbcTemplate(
                new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
    }

    void record(Path output, int repetitions) throws IOException {
        Files.createDirectories(output);
        long initialRows = jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Long.class);
        long lastId = jdbc.queryForObject("SELECT MAX(id) FROM reservations", Long.class);
        assertThat(indexDefinition("reservations")).anySatisfy(index ->
                assertThat(index.get("indexName")).isEqualTo(indexName));

        // given: 스키마와 데이터를 복제하고 후보 인덱스 유무만 다르게 한다.
        for (Variant variant : Variant.values()) {
            createCopy(variant, initialRows);
            write(output.resolve(variant.label + "-schema.txt"),
                    jdbc.queryForList("SHOW CREATE TABLE " + variant.table).toString());
        }
        List<Map<String, Object>> withoutIndexes = indexDefinition(Variant.WITHOUT.table);
        List<Map<String, Object>> withIndexes = indexDefinition(Variant.WITH.table);
        assertThat(withoutIndexes).noneSatisfy(index -> assertThat(index.get("indexName")).isEqualTo(indexName));
        assertThat(withIndexes.stream().filter(index -> !indexName.equals(index.get("indexName"))).toList())
                .containsExactlyElementsOf(withoutIndexes);
        recordSizes(output.resolve("sizes-at-100000-rows.txt"), initialRows);

        List<OwnerTicket> owners = jdbc.query("SELECT user_id, MIN(ticket_id) AS ticket_id FROM reservations "
                        + "GROUP BY user_id ORDER BY user_id",
                (row, index) -> new OwnerTicket(row.getLong("user_id"), row.getLong("ticket_id")));
        int slotsPerBatch = (BATCH_SIZE + owners.size() - 1) / owners.size();
        int firstSlot = prepareInsertSlots(owners, slotsPerBatch * (repetitions + 1));
        List<Long> updateIds = jdbc.queryForList("SELECT id FROM reservations WHERE reservation_status='RESERVED' "
                + "ORDER BY id LIMIT " + (repetitions + 1) * BATCH_SIZE, Long.class);
        assertThat(updateIds).hasSize((repetitions + 1) * BATCH_SIZE);
        Timestamp changedAt = Timestamp.valueOf(LocalDate.of(2026, 10, 20).atTime(10, 0));
        List<Trial> trials = new ArrayList<>();

        // when: 1회 워밍업 후 반복 측정한다. 매 반복마다 실행 순서를 바꿔 고정 순서 편향을 줄인다.
        // 각 테이블의 행 수는 동일하게 증가한다. 삭제·원복을 반복하는 비용은 끼워 넣지 않는다.
        for (int iteration = 0; iteration <= repetitions; iteration++) {
            List<Object[]> inserts = insertRows(lastId + (long) iteration * BATCH_SIZE, owners,
                    firstSlot + iteration * slotsPerBatch, changedAt);
            List<Long> ids = updateIds.subList(iteration * BATCH_SIZE, (iteration + 1) * BATCH_SIZE);
            List<Variant> order = iteration % 2 == 0
                    ? List.of(Variant.WITHOUT, Variant.WITH) : List.of(Variant.WITH, Variant.WITHOUT);
            for (Variant variant : order) {
                long rowsBefore = initialRows + (long) iteration * BATCH_SIZE;
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + variant.table, Long.class))
                        .isEqualTo(rowsBefore);
                trials.add(insert(variant, iteration, rowsBefore, inserts));
                trials.add(update(variant, iteration, rowsBefore + BATCH_SIZE, ids, changedAt));
            }
        }

        // then: 두 테이블의 행 수·상태 분포가 같고 원본 예약에는 쓰기 작업이 없었는지 확인한다.
        long finalRows = initialRows + (long) (repetitions + 1) * BATCH_SIZE;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Long.class)).isEqualTo(initialRows);
        for (Variant variant : Variant.values()) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + variant.table, Long.class)).isEqualTo(finalRows);
        }
        assertThat(statusDistribution(Variant.WITH.table)).containsExactlyElementsOf(statusDistribution(Variant.WITHOUT.table));
        recordSizes(output.resolve("sizes-after-writes.txt"), finalRows);
        write(output.resolve("trials.csv"), trialsCsv(trials));
        write(output.resolve("summary.txt"), summary(trials));
        write(output.resolve("method.txt"), "issue=" + issue + "\nindexName=" + indexName
                + "\ninitialRowsPerTable=" + initialRows
                + "\nrowsPerOperation=" + BATCH_SIZE + "\nwarmupIterations=1\nmeasuredIterations=" + repetitions
                + "\nfinalRowsPerTable=" + finalRows
                + "\norder=without/with on even iteration; with/without on odd iteration"
                + "\ntiming=JDBC batch + transaction begin/commit; fixture, verification, DDL and ANALYZE excluded"
                + "\ninsert=RESERVED; explicit identical IDs and values; additional slots avoid active uniqueness conflicts"
                + "\nupdate=disjoint original RESERVED rows to CANCELLED; cancellation reason/time and updated_at included"
                + "\nconstraints=both copies retain UNIQUE/generated column/indexes; FK constraints explicitly recreated"
                + "\nsize=persistent index page statistics times innodb_page_size; estimate, not exact file usage"
                + "\nlimits=single-thread synthetic SQL workload; no Service, slot count update, HTTP, concurrent locks or production load"
                + "\nlimits=table/cache/page layout and background IO can influence timings; 5 repetitions do not establish statistical significance\n");
    }

    private void createCopy(Variant variant, long expectedRows) {
        jdbc.execute("CREATE TABLE " + variant.table + " LIKE reservations");
        if (variant == Variant.WITHOUT) {
            jdbc.execute("ALTER TABLE " + variant.table + " DROP INDEX " + indexName);
        }
        // CREATE TABLE LIKE는 FK를 복제하지 않으므로 양쪽에 동일한 참조 관계를 다시 추가한다.
        // https://dev.mysql.com/doc/refman/8.4/en/create-table-like.html
        jdbc.execute("ALTER TABLE " + variant.table
                + " ADD CONSTRAINT fk_index_cost_" + variant.label + "_user FOREIGN KEY (user_id) REFERENCES users(id),"
                + " ADD CONSTRAINT fk_index_cost_" + variant.label + "_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),"
                + " ADD CONSTRAINT fk_index_cost_" + variant.label + "_slot FOREIGN KEY (attraction_slot_id) REFERENCES attraction_slots(id)");
        transaction.executeWithoutResult(status -> jdbc.update("INSERT INTO " + variant.table + " (" + COLUMNS
                + ") SELECT " + COLUMNS + " FROM reservations ORDER BY id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + variant.table, Long.class)).isEqualTo(expectedRows);
    }

    private int prepareInsertSlots(List<OwnerTicket> owners, int slotCount) {
        assertThat(owners).isNotEmpty();
        int firstSlot = jdbc.queryForObject("SELECT MAX(id) + 1 FROM attraction_slots", Integer.class);
        LocalDate visitDate = jdbc.queryForObject("SELECT valid_date FROM tickets WHERE id=?",
                LocalDate.class, owners.get(0).ticketId());
        Timestamp createdAt = Timestamp.valueOf(visitDate.atStartOfDay());
        long attractionId = jdbc.queryForObject("SELECT MAX(id) + 1 FROM attractions", Long.class);
        jdbc.update("INSERT INTO attractions (id,created_at,updated_at,description,duration_minutes,"
                        + "entrance_move_minutes,location,name,operation_status) "
                        + "VALUES (?,?,?,'쓰기 비용 측정용',1,1,'A구역',?,'OPERATING')",
                attractionId, createdAt, createdAt, issue + " 쓰기 측정 시설");
        for (int slot = 0; slot < slotCount; slot++) {
            var start = visitDate.atTime(20, 0).plusMinutes(slot);
            jdbc.update("INSERT INTO attraction_slots (id,created_at,updated_at,auto_closed,capacity,ends_at,"
                            + "reserved_count,starts_at,status,attraction_id) VALUES (?,?,?,false,?,?,0,?,'OPEN',?)",
                    firstSlot + slot, createdAt, createdAt, owners.size(), start.plusMinutes(1), start, attractionId);
        }
        return firstSlot;
    }

    private List<Object[]> insertRows(long lastId, List<OwnerTicket> owners, int firstSlot, Timestamp time) {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < BATCH_SIZE; index++) {
            OwnerTicket owner = owners.get(index % owners.size());
            long id = lastId + index + 1;
            // 호출자가 반복별로 다른 슬롯 범위를 전달하여 활성 예약 UNIQUE 충돌을 방지한다.
            rows.add(new Object[]{id, time, time, "KAN130-W-" + id,
                    firstSlot + index / owners.size(), owner.ticketId(), owner.userId()});
        }
        return rows;
    }

    private Trial insert(Variant variant, int iteration, long rowsBefore, List<Object[]> rows) {
        long started = System.nanoTime();
        transaction.executeWithoutResult(status -> batch("INSERT INTO " + variant.table
                + " (id,created_at,updated_at,reservation_number,reservation_status,attraction_slot_id,ticket_id,user_id) "
                + "VALUES (?,?,?,?,'RESERVED',?,?,?)", rows));
        double milliseconds = (System.nanoTime() - started) / 1_000_000.0;
        long firstId = (Long) rows.get(0)[0];
        long lastId = (Long) rows.get(rows.size() - 1)[0];
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + variant.table
                + " WHERE id BETWEEN ? AND ? AND reservation_status='RESERVED'", Long.class, firstId, lastId))
                .isEqualTo(BATCH_SIZE);
        return trial(variant, "insert", iteration, rowsBefore, milliseconds);
    }

    private Trial update(Variant variant, int iteration, long rowsBefore, List<Long> ids, Timestamp time) {
        List<Object[]> rows = ids.stream().map(id -> new Object[]{time, time, id}).toList();
        long started = System.nanoTime();
        transaction.executeWithoutResult(status -> batch("UPDATE " + variant.table
                + " SET reservation_status='CANCELLED', cancel_reason='USER_REQUEST', cancelled_at=?, updated_at=?"
                + " WHERE id=? AND reservation_status='RESERVED'", rows));
        double milliseconds = (System.nanoTime() - started) / 1_000_000.0;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        assertThat(jdbc.queryForList("SELECT id FROM " + variant.table + " WHERE id IN (" + placeholders
                        + ") AND reservation_status='CANCELLED' AND cancel_reason='USER_REQUEST'"
                        + " AND cancelled_at IS NOT NULL AND active_reservation IS NULL", Long.class, ids.toArray()))
                .containsExactlyInAnyOrderElementsOf(ids);
        return trial(variant, "update", iteration, rowsBefore, milliseconds);
    }

    private Trial trial(Variant variant, String operation, int iteration, long rowsBefore, double milliseconds) {
        Trial trial = new Trial(variant, operation, iteration, rowsBefore, milliseconds);
        System.out.printf(Locale.ROOT, "event=RESERVATION_INDEX_WRITE_MEASURED issue=%s indexName=%s variant=%s operation=%s "
                        + "iteration=%d warmup=%s count=%d rowsBefore=%d durationMs=%.3f%n",
                issue, indexName, variant.label, operation, iteration, iteration == 0, BATCH_SIZE, rowsBefore, milliseconds);
        return trial;
    }

    private List<Map<String, Object>> indexDefinition(String table) {
        return jdbc.queryForList("SELECT INDEX_NAME AS indexName, NON_UNIQUE AS nonUnique, SEQ_IN_INDEX AS sequenceNumber, "
                + "COLUMN_NAME AS columnName, COLLATION AS sortDirection FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY INDEX_NAME,SEQ_IN_INDEX", table);
    }

    private List<Map<String, Object>> statusDistribution(String table) {
        return jdbc.queryForList("SELECT reservation_status, COUNT(*) AS count FROM " + table
                + " GROUP BY reservation_status ORDER BY reservation_status");
    }

    private void recordSizes(Path path, long rowCount) throws IOException {
        // 인덱스별 size 통계는 페이지 수다. 페이지 크기를 곱한 추정치를 보존한다.
        // https://dev.mysql.com/doc/refman/8.4/en/innodb-persistent-stats.html
        jdbc.queryForList("ANALYZE TABLE " + Variant.WITHOUT.table + ", " + Variant.WITH.table);
        List<Map<String, Object>> sizes = statisticsJdbc.queryForList(
                "SELECT table_name AS tableName, index_name AS indexName, stat_value AS pages, "
                        + "stat_value * @@innodb_page_size AS estimatedBytes FROM mysql.innodb_index_stats "
                        + "WHERE database_name=DATABASE() AND table_name IN (?,?) AND stat_name='size' "
                        + "ORDER BY table_name,index_name", Variant.WITHOUT.table, Variant.WITH.table);
        assertThat(sizes).isNotEmpty();
        assertThat(sizes).anySatisfy(size -> {
            assertThat(size.get("tableName")).isEqualTo(Variant.WITH.table);
            assertThat(size.get("indexName")).isEqualTo(indexName);
            assertThat(((Number) size.get("estimatedBytes")).longValue()).isPositive();
        });
        write(path, "indexName=" + indexName + "\nrowsPerTable=" + rowCount + "\npageSizeBytes="
                + statisticsJdbc.queryForObject("SELECT @@innodb_page_size", Long.class)
                + "\nsource=mysql.innodb_index_stats, stat_name=size\n" + sizes);
    }

    private String trialsCsv(List<Trial> trials) {
        StringBuilder csv = new StringBuilder("variant,operation,iteration,warmup,count,rowsBefore,durationMs\n");
        for (Trial trial : trials) {
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%s,%d,%d,%.6f%n", trial.variant().label,
                    trial.operation(), trial.iteration(), trial.iteration() == 0, BATCH_SIZE,
                    trial.rowsBefore(), trial.milliseconds()));
        }
        return csv.toString();
    }

    private String summary(List<Trial> trials) {
        StringBuilder summary = new StringBuilder("issue=" + issue + "\nindexName=" + indexName
                + "\nunit=ms per 1000 rows including transaction commit\n");
        for (Variant variant : Variant.values()) {
            for (String operation : List.of("insert", "update")) {
                List<Double> values = trials.stream().filter(trial -> trial.variant() == variant
                                && trial.operation().equals(operation) && trial.iteration() > 0)
                        .map(Trial::milliseconds).sorted().toList();
                double median = values.size() % 2 == 1 ? values.get(values.size() / 2)
                        : (values.get(values.size() / 2 - 1) + values.get(values.size() / 2)) / 2;
                summary.append(String.format(Locale.ROOT, "%s %s values=%s medianMs=%.6f%n",
                        variant.label, operation, values, median));
            }
        }
        return summary.toString();
    }

    private void batch(String sql, List<Object[]> rows) {
        jdbc.batchUpdate(sql, rows, BATCH_SIZE, (statement, values) -> {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
        });
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private enum Variant {
        WITHOUT("without", "reservation_cost_without_index"),
        WITH("with", "reservation_cost_with_index");

        private final String label;
        private final String table;

        Variant(String label, String table) {
            this.label = label;
            this.table = table;
        }
    }

    private record OwnerTicket(long userId, long ticketId) {
    }

    private record Trial(Variant variant, String operation, int iteration, long rowsBefore, double milliseconds) {
    }
}
