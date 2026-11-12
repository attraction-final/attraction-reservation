package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// AttractionRepositoryImpl.searchAttractions() 테스트
// H2(테스트 DB)에 데이터를 실제로 넣고, QueryDSL 쿼리를 실제로 실행해서 결과를 확인한다.
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class}) // JPAQueryFactory 빈도 같이 띄움
class AttractionRepositoryTest {

    // 기준 날짜를 고정 → 테스트를 언제 돌려도 결과가 같다.
    private static final LocalDateTime DAY = LocalDateTime.of(2030, 1, 1, 0, 0);
    private static final LocalDateTime RESERVABLE_FROM = DAY.withHour(10).withMinute(30); // "지금 + 30분"이라고 가정
    private static final LocalDateTime TODAY_END = DAY.plusDays(1);

    @Autowired
    private AttractionRepository attractionRepository;

    @Autowired
    private AttractionSlotRepository attractionSlotRepository;

    @BeforeEach
    void setUp() {
        // 롤러코스터: 운영중 + 예약 가능한 슬롯 있음
        Attraction rollerCoaster = attractionRepository.save(
                Attraction.create("롤러코스터", "빠른 코스터", "A구역", 10, 5));

        // 회전목마: 점검중 (OPEN 슬롯은 있지만 어트랙션이 운영 안 함)
        Attraction carousel = Attraction.create("회전목마", "느린 놀이기구", "B구역", 10, 5);
        carousel.changeOperationStatus(OperationStatus.MAINTENANCE);
        attractionRepository.save(carousel);

        // 바이킹: 운영중이지만 슬롯 없음
        attractionRepository.save(Attraction.create("바이킹", "흔들리는 배", "C구역", 10, 5));

        // 삭제된 어트랙션: 어떤 조건에서도 나오면 안 됨
        Attraction deleted = Attraction.create("삭제된 코스터", "삭제됨", "D구역", 10, 5);
        deleted.deleted();
        attractionRepository.save(deleted);

        // 롤러코스터 슬롯: 예약 가능 1개 / 정원 마감 1개
        AttractionSlot available = AttractionSlot.create(rollerCoaster, DAY.withHour(14), 10);
        AttractionSlot full = AttractionSlot.create(rollerCoaster, DAY.withHour(15), 5);
        ReflectionTestUtils.setField(full, "reservedCount", 5); // 잔여 0

        // 회전목마 슬롯: OPEN, 잔여 있음
        AttractionSlot carouselSlot = AttractionSlot.create(carousel, DAY.withHour(14), 10);

        attractionSlotRepository.saveAll(List.of(available, full, carouselSlot));
        attractionSlotRepository.flush();
    }

    // 자주 쓰는 호출을 짧게 묶은 도우미
    private Page<Attraction> search(String keyword, boolean operatingOnly, boolean reservableOnly, String sort) {
        return attractionRepository.searchAttractions(
                keyword, operatingOnly, reservableOnly, null, sort,
                RESERVABLE_FROM, TODAY_END, PageRequest.of(0, 10));
    }

    @Test
    @DisplayName("조건이 없으면 삭제되지 않은 어트랙션을 이름순으로 조회한다")
    void searchAttractions_NoCondition_ReturnsNotDeletedSortedByName() {
        // when
        Page<Attraction> result = search(null, false, false, "NAME");

        // then
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터", "바이킹", "회전목마");
    }

    @Test
    @DisplayName("검색어가 이름에 포함된 어트랙션만 조회하고, 삭제된 어트랙션은 제외한다")
    void searchAttractions_Keyword_ReturnsMatchedOnly() {
        // when
        Page<Attraction> result = search("코스터", false, false, "NAME");

        // then
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터"); // "삭제된 코스터"는 빠져야 함
    }

    @Test
    @DisplayName("검색어와 일치하는 어트랙션이 없으면 빈 페이지를 반환한다")
    void searchAttractions_NoMatchedKeyword_ReturnsEmptyPage() {
        // when
        Page<Attraction> result = search("없는이름", false, false, "NAME");

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("운영중만 보기를 켜면 운영중인 어트랙션만 조회한다")
    void searchAttractions_OperatingOnly_ReturnsOperatingOnly() {
        // when
        Page<Attraction> result = search(null, true, false, "NAME");

        // then
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터", "바이킹");
    }

    @Test
    @DisplayName("예약 가능만 보기를 켜면 운영중이면서 예약 가능한 슬롯이 있는 어트랙션만 조회한다")
    void searchAttractions_ReservableOnly_ReturnsReservableOnly() {
        // when
        Page<Attraction> result = search(null, false, true, "NAME");

        // then
        // 회전목마: 슬롯은 있지만 점검중 → 제외
        // 바이킹: 운영중이지만 슬롯 없음 → 제외
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터");
        assertThat(result.getTotalElements()).isEqualTo(1); // count 쿼리도 같은 조건인지 확인
    }

    @Test
    @DisplayName("페이지 크기보다 데이터가 많으면 나눠서 조회하고 전체 개수는 그대로다")
    void searchAttractions_Paging_ReturnsPartialContentAndTotalCount() {
        // when
        Page<Attraction> firstPage = attractionRepository.searchAttractions(
                null, false, false, null, "NAME", RESERVABLE_FROM, TODAY_END, PageRequest.of(0, 2));
        Page<Attraction> secondPage = attractionRepository.searchAttractions(
                null, false, false, null, "NAME", RESERVABLE_FROM, TODAY_END, PageRequest.of(1, 2));

        // then
        assertThat(firstPage.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터", "바이킹");
        assertThat(secondPage.getContent())
                .extracting(Attraction::getName)
                .containsExactly("회전목마");
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("페이지를 나누지 않는 요청(unpaged)이면 조건에 맞는 어트랙션을 전부 조회한다")
    void searchAttractions_Unpaged_ReturnsAllContent() {
        // when
        Page<Attraction> result = attractionRepository.searchAttractions(
                null, false, false, null, "NAME", RESERVABLE_FROM, TODAY_END, Pageable.unpaged());

        // then
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터", "바이킹", "회전목마");
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("페이지를 나누지 않아도 검색 조건은 그대로 적용된다")
    void searchAttractions_UnpagedWithCondition_ReturnsMatchedOnly() {
        // when
        Page<Attraction> result = attractionRepository.searchAttractions(
                null, true, false, null, "NAME", RESERVABLE_FROM, TODAY_END, Pageable.unpaged());

        // then
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터", "바이킹"); // 점검중인 회전목마 제외
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("Q&A 분류 대상은 삭제된 어트랙션을 빼고 점검 중인 어트랙션은 포함해 id 순으로 조회한다")
    void findAllByDeletedAtIsNullOrderByIdAsc_ExcludesDeletedOnly() {
        // when
        List<Attraction> result = attractionRepository.findAllByDeletedAtIsNullOrderByIdAsc();

        //then
        assertThat(result)
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터", "회전목마", "바이킹");
        //점검중인 회전목마는 상태 질문에 답해야 하므로 포함
    }

    // ===== 키 필터(rideableAt) 테스트 =====
    // setUp 의 롤러코스터·회전목마·바이킹은 키 정보가 없다(NULL) → 키 필터를 쓰면 항상 빠져야 한다.

    // 키 정보를 넣은 어트랙션을 저장하는 도우미
    // 엔티티에 키를 바꾸는 메서드가 없어서 ReflectionTestUtils 로 필드에 직접 값을 넣는다.
    private Attraction saveWithHeight(String name, Integer minHeightCm, Integer maxHeightCm, Integer guardianHeightCm) {
        Attraction attraction = Attraction.create(name, "키 제한 테스트", "E구역", 10, 5);
        ReflectionTestUtils.setField(attraction, "minHeightCm", minHeightCm);
        ReflectionTestUtils.setField(attraction, "maxHeightCm", maxHeightCm);
        ReflectionTestUtils.setField(attraction, "guardianHeightCm", guardianHeightCm);
        return attractionRepository.save(attraction);
    }

    // 키 필터 테스트용 데이터 4개
    private void saveHeightAttractions() {
        saveWithHeight("최소최대", 130, 195, null);   // 130~195cm
        saveWithHeight("보호자동반", 110, null, 110); // 110cm 이상, 110cm 이하는 보호자 동반
        saveWithHeight("최대만", null, 120, null);    // 120cm 이하
        saveWithHeight("보호자만", null, null, 100);  // 최소/최대 없이 보호자 기준만 있음
        attractionRepository.flush();
    }

    // 키만 바꿔 가며 조회하는 도우미
    private Page<Attraction> searchByHeight(Integer height) {
        return attractionRepository.searchAttractions(
                null, false, false, height, "NAME",
                RESERVABLE_FROM, TODAY_END, PageRequest.of(0, 10));
    }

    @Test
    @DisplayName("키를 입력하지 않으면 키 필터 없이 전체를 조회한다")
    void searchAttractions_HeightNull_ReturnsAllWithoutHeightFilter() {
        // given
        saveHeightAttractions();

        // when
        Page<Attraction> result = searchByHeight(null);

        // then: 키 정보가 없는 3개 + 키 정보가 있는 4개 = 7개 (삭제된 어트랙션 제외)
        assertThat(result.getTotalElements()).isEqualTo(7);
    }

    @Test
    @DisplayName("키를 입력하면 키 정보가 없는 어트랙션은 제외한다")
    void searchAttractions_Height_ExcludesNoHeightInfo() {
        // when: 키 정보가 있는 어트랙션이 하나도 없는 상태
        Page<Attraction> result = searchByHeight(150);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("최소 키보다 작으면 제외하고, 최소 키와 같으면 포함한다")
    void searchAttractions_Height_AppliesMinHeightBoundary() {
        // given
        saveHeightAttractions();

        // when
        Page<Attraction> result = searchByHeight(110);

        // then
        // 최소최대(130 이상) 제외 / 보호자동반(110 이상) 경계값이라 포함
        // 최대만(120 이하) 포함 / 보호자만(최소·최대 없음) 포함
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactlyInAnyOrder("보호자동반", "최대만", "보호자만");
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("최대 키보다 크면 제외하고, 최대 키와 같으면 포함한다")
    void searchAttractions_Height_AppliesMaxHeightBoundary() {
        // given
        saveHeightAttractions();

        // when
        Page<Attraction> atMax = searchByHeight(120);
        Page<Attraction> overMax = searchByHeight(121);

        // then
        // 120cm: 최대만(120 이하) 경계값이라 포함
        assertThat(atMax.getContent())
                .extracting(Attraction::getName)
                .containsExactlyInAnyOrder("보호자동반", "최대만", "보호자만");
        // 121cm: 최대만 제외
        assertThat(overMax.getContent())
                .extracting(Attraction::getName)
                .containsExactlyInAnyOrder("보호자동반", "보호자만");
    }

    @Test
    @DisplayName("최소 키와 최대 키가 모두 있으면 그 사이 키만 포함한다")
    void searchAttractions_Height_AppliesMinAndMaxRange() {
        // given
        saveHeightAttractions();

        // when
        Page<Attraction> inRange = searchByHeight(195);
        Page<Attraction> overRange = searchByHeight(196);

        // then
        // 195cm: 최소최대(130~195) 경계값이라 포함 / 최대만(120 이하) 제외
        assertThat(inRange.getContent())
                .extracting(Attraction::getName)
                .containsExactlyInAnyOrder("최소최대", "보호자동반", "보호자만");
        // 196cm: 최소최대 제외
        assertThat(overRange.getContent())
                .extracting(Attraction::getName)
                .containsExactlyInAnyOrder("보호자동반", "보호자만");
    }

    @Test
    @DisplayName("보호자 동반 기준보다 작아도 최소 키보다 작으면 제외한다")
    void searchAttractions_Height_ExcludesGuardianOnlyRide() {
        // given
        saveHeightAttractions();

        // when: 보호자와 함께라면 탈 수 있는 키지만, 혼자 탈 수 있는 키(110)보다 작다
        Page<Attraction> result = searchByHeight(105);

        // then
        // 보호자동반(110 이상)·최소최대(130 이상) 제외 / 최대만(120 이하)·보호자만 포함
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactlyInAnyOrder("최대만", "보호자만");
    }

    @Test
    @DisplayName("키 필터는 다른 조건과 함께 적용되고 전체 개수에도 반영된다")
    void searchAttractions_HeightWithOperatingOnly_AppliesBothConditions() {
        // given
        saveHeightAttractions();
        Attraction maintenance = saveWithHeight("점검중키", 100, null, null);
        maintenance.changeOperationStatus(OperationStatus.MAINTENANCE);
        attractionRepository.flush();

        // when
        Page<Attraction> result = attractionRepository.searchAttractions(
                null, true, false, 110, "NAME",
                RESERVABLE_FROM, TODAY_END, PageRequest.of(0, 2));

        // then: 운영중 + 110cm로 탈 수 있음 = 보호자동반, 최대만, 보호자만 (점검중키 제외)
        assertThat(result.getContent()).hasSize(2);           // 페이지 크기 2
        assertThat(result.getTotalElements()).isEqualTo(3);   // 전체 개수에도 키 필터 반영
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .doesNotContain("점검중키", "최소최대");
    }
}