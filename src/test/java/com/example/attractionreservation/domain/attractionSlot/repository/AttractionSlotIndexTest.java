package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 인덱스는 진짜 MySQL 테이블에 있는지 확인해야 하므로 Testcontainers MySQL 사용
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class AttractionSlotIndexTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("attraction_slots 에 (attraction_id, starts_at) 순서의 복합 인덱스가 있다")
    void compositeIndexExists() {
        // information_schema.statistics = MySQL이 가진 "인덱스 목록표"
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = 'attraction_slots'
                   AND index_name = 'idx_attraction_slots_attraction_starts'
                 ORDER BY seq_in_index
                """, String.class);

        // 컬럼 "순서"가 중요하다: attraction_id 가 먼저여야 "어트랙션 → 시간" 순으로 찾는다
        assertThat(columns).containsExactly("attraction_id", "starts_at");
    }
}
