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
                keyword, operatingOnly, reservableOnly, sort,
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
                null, false, false, "NAME", RESERVABLE_FROM, TODAY_END, PageRequest.of(0, 2));
        Page<Attraction> secondPage = attractionRepository.searchAttractions(
                null, false, false, "NAME", RESERVABLE_FROM, TODAY_END, PageRequest.of(1, 2));

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
                null, false, false, "NAME", RESERVABLE_FROM, TODAY_END, Pageable.unpaged());

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
                null, true, false, "NAME", RESERVABLE_FROM, TODAY_END, Pageable.unpaged());

        // then
        assertThat(result.getContent())
                .extracting(Attraction::getName)
                .containsExactly("롤러코스터", "바이킹"); // 점검중인 회전목마 제외
        assertThat(result.getTotalElements()).isEqualTo(2);
    }


}
