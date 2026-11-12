package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// 탑승 정보 저장과 마이그레이션 데이터를 H2로 검증한다.
// docs/sql 파일은 앱이 실행하지 않아 오타가 나도 아무도 모른다. 운영에 넣을 파일을 그대로 읽어 값을 비교한다.
// ALTER는 create-drop으로 만든 스키마에 이미 반영되어 있어 UPDATE 문만 실행한다.
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class AttractionRideInfoTest {

    private static final Path MIGRATION_SQL = Path.of("docs/sql/kan-117-attraction-ride-info-migration.sql");

    private static final List<String> ATTRACTION_NAMES = List.of(
            "아마존 익스프레스", "모니모 러쉬", "범퍼카", "썬더폴스", "우주전투기",
            "콜럼버스 대탐험", "더블 락스핀", "렛츠 트위스트", "롤링 엑스 트레인", "허리케인");

    @Autowired
    private AttractionRepository attractionRepository;

    @Autowired
    private EntityManager entityManager;

    private Attraction saveAttraction(String name) {
        return attractionRepository.save(Attraction.create(name, "설명", "A구역", 10, 5));
    }

    private void applyMigrationUpdates() throws IOException {
        entityManager.flush();
        Arrays.stream(Files.readString(MIGRATION_SQL).split(";"))
                .map(this::removeCommentLines)
                .filter(statement -> statement.startsWith("UPDATE"))
                .forEach(statement -> entityManager.createNativeQuery(statement).executeUpdate());
        entityManager.clear(); // 1차 캐시가 아니라 DB 값을 읽도록 비운다
    }

    private String removeCommentLines(String statement) {
        return statement.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .strip();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", textBlock = """
            # 어트랙션,       단독 최소 키, 최대 키, 보호자 기준 키, 시작, 종료
            아마존 익스프레스, 110, null, 110,  10:30, 20:00
            모니모 러쉬,       130, 195,  null, 12:45, 19:30
            범퍼카,            120, null, null, 10:00, 20:00
            썬더폴스,          100, null, null, 10:30, 20:00
            우주전투기,        110, null, null, 10:00, 13:00
            콜럼버스 대탐험,   110, null, null, 10:45, 20:00
            더블 락스핀,       140, null, null, 11:00, 20:00
            렛츠 트위스트,     140, 195,  null, null,  null
            롤링 엑스 트레인,  120, null, null, 10:00, 20:30
            허리케인,          130, null, null, 10:00, 20:30
            """)
    @DisplayName("마이그레이션을 적용하면 어트랙션별 탑승 정보가 표와 같다")
    void migration_appliesRideInfo(String name, Integer minHeightCm, Integer maxHeightCm, Integer guardianHeightCm,
                                   LocalTime openTime, LocalTime closeTime) throws IOException {
        // given
        ATTRACTION_NAMES.forEach(this::saveAttraction);

        // when
        applyMigrationUpdates();

        // then
        assertThat(attractionRepository.findAll())
                .filteredOn(attraction -> attraction.getName().equals(name))
                .singleElement()
                .extracting(
                        Attraction::getMinHeightCm, Attraction::getMaxHeightCm, Attraction::getGuardianHeightCm,
                        Attraction::getOpenTime, Attraction::getCloseTime)
                .containsExactly(minHeightCm, maxHeightCm, guardianHeightCm, openTime, closeTime);
    }

    @Test
    @DisplayName("마이그레이션은 삭제된 어트랙션을 건드리지 않는다")
    void migration_skipsDeletedAttraction() throws IOException {
        // given
        Attraction deleted = saveAttraction("범퍼카");
        deleted.deleted();

        // when
        applyMigrationUpdates();

        // then
        Attraction result = attractionRepository.findById(deleted.getId()).orElseThrow();
        assertThat(result.getMinHeightCm()).isNull();
    }

    @Test
    @DisplayName("탑승 정보를 넣지 않은 어트랙션은 모든 값이 NULL로 저장된다 (시나리오 10)")
    void create_leavesRideInfoNull() {
        // given
        Attraction attraction = saveAttraction("새 어트랙션");
        entityManager.flush();
        entityManager.clear();

        // when
        Attraction result = attractionRepository.findById(attraction.getId()).orElseThrow();

        // then
        assertThat(result)
                .extracting(
                        Attraction::getMinHeightCm, Attraction::getMaxHeightCm, Attraction::getGuardianHeightCm,
                        Attraction::getOpenTime, Attraction::getCloseTime)
                .containsOnlyNulls();
    }
}