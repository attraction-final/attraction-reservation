package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationCondition;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// KAN-129: 실제 Service의 관리자 목록 조회와 DTO 변환까지 포함해 SELECT 수를 측정한다.
// 스키마 이행 검증은 FlywayMigrationIntegrationTest가 담당하며, 여기서는 엔티티로 fixture 스키마를 만든다.
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QuerydslConfig.class, ReservationService.class,
        AdminReservationListQueryIntegrationTest.MySqlConfig.class})
class AdminReservationListQueryIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    private static final int RESERVATION_COUNT = 50;
    // 목록 SELECT 1개 + count SELECT 1개를 개선 목표로 둔다.
    private static final long MAX_SELECT_COUNT = 2;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private EntityManager entityManager;

    // 목록이 사용하지 않는 협력 Service만 mock으로 대체한다. Repository·DTO는 실제 실행한다.
    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private AttractionSlotService attractionSlotService;

    private Statistics statistics;
    private final List<ExpectedReservation> expectedReservations = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            // 프로젝트의 Flyway 문서·운영 RDS와 같은 버전으로 측정한다.
            return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
        }
    }

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        User firstUser = createUser("관리자조회회원1");
        User secondUser = createUser("관리자조회회원2");
        Ticket firstTicket = createEnteredTicket(firstUser);
        Ticket secondTicket = createEnteredTicket(secondUser);

        // 여러 회원의 예약을 번갈아 저장한다. 10건 페이지에도 두 회원이 모두 포함된다.
        // 서로 다른 슬롯·어트랙션을 사용해 1차 캐시가 추가 연관 조회를 가리지 않게 한다.
        for (int index = 0; index < RESERVATION_COUNT; index++) {
            User owner = index % 2 == 0 ? firstUser : secondUser;
            Ticket ticket = index % 2 == 0 ? firstTicket : secondTicket;
            Reservation reservation = createReservation(owner, ticket, index);
            AttractionSlot slot = reservation.getAttractionSlot();
            expectedReservations.add(new ExpectedReservation(
                    reservation.getId(), reservation.getReservationNumber(), owner.getId(),
                    slot.getAttraction().getId(), slot.getAttraction().getName(), slot.getId(),
                    slot.getStartsAt(), slot.getEndsAt()));
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("관리자 예약 목록은 필터 없이 10건과 50건을 DTO로 변환해도 SELECT 수가 일정하다")
    void findAdminReservationAll_withoutFilters_keepsSelectCountConstant() {
        // given
        SearchAdminReservationCondition condition = new SearchAdminReservationCondition(null, null, null, null);

        // when
        Measurement smallPage = measure(10, condition);
        Measurement largePage = measure(50, condition);

        // then
        assertPage(smallPage.page(), 10);
        assertPage(largePage.page(), 50);
        // 응답 검증과 측정 로그를 먼저 남겨, 개선 전 실패에서도 재현 지표를 확인할 수 있게 한다.
        assertSoftly(softly -> {
            softly.assertThat(smallPage.statementCount()).as("10건: 목록·count를 포함한 SELECT 수")
                    .isLessThanOrEqualTo(MAX_SELECT_COUNT);
            softly.assertThat(largePage.statementCount()).as("50건: 목록·count를 포함한 SELECT 수")
                    .isLessThanOrEqualTo(MAX_SELECT_COUNT);
            softly.assertThat(largePage.statementCount()).as("조회 건수가 늘어도 SELECT 수가 증가하지 않음")
                    .isEqualTo(smallPage.statementCount());
        });
    }

    @Test
    @DisplayName("관리자 예약 목록은 지정한 회원의 예약만 조회하고 전체 건수도 필터를 적용한다")
    void findAdminReservationAll_withUserId_returnsOnlyMatchingUser() {
        // given
        Long userId = expectedReservations.get(0).userId();
        SearchAdminReservationCondition condition = new SearchAdminReservationCondition(userId, null, null, null);
        List<Long> expectedIds = expectedReservations.stream()
                .filter(item -> item.userId().equals(userId))
                .map(ExpectedReservation::reservationId).sorted(Comparator.reverseOrder()).toList();

        // when
        Page<SearchAdminReservationResponse> page = queryForRegression(condition, 0, 50);

        // then
        assertReservationIds(page, expectedIds, 25);
        assertThat(page.getContent()).allSatisfy(item -> assertThat(item.userId()).isEqualTo(userId));
    }

    @Test
    @DisplayName("관리자 예약 목록은 같은 어트랙션의 여러 슬롯과 여러 회원 예약을 모두 조회한다")
    void findAdminReservationAll_withAttractionId_returnsAllMatchingSlotsAndUsers() {
        // given
        ExpectedReservation first = expectedReservations.get(0);
        Attraction attraction = entityManager.find(Attraction.class, first.attractionId());
        User firstUser = entityManager.find(User.class, first.userId());
        User secondUser = entityManager.find(User.class, expectedReservations.get(1).userId());
        Reservation extraFirst = persistReservation(firstUser, createEnteredTicket(firstUser), attraction,
                DAY.atTime(20, 0));
        Reservation extraSecond = persistReservation(secondUser, createEnteredTicket(secondUser), attraction,
                DAY.atTime(20, 10));
        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(null, first.attractionId(), null, null);

        // when
        Page<SearchAdminReservationResponse> page = queryForRegression(condition, 0, 50);

        // then
        assertReservationIds(page, List.of(extraSecond.getId(), extraFirst.getId(), first.reservationId()), 3);
        assertThat(page.getContent()).allSatisfy(item ->
                assertThat(item.attractionId()).isEqualTo(first.attractionId()));
        assertThat(page.getContent()).extracting(SearchAdminReservationResponse::userId)
                .contains(firstUser.getId(), secondUser.getId());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CANCELLED", "COMPLETED", "NO_SHOW"})
    @DisplayName("관리자 예약 목록은 취소·이용 완료·노쇼 상태를 구분하고 RESERVED 조회에서 제외한다")
    void findAdminReservationAll_withStatus_returnsOnlyMatchingStatus(ReservationStatus status) {
        // given
        ExpectedReservation target = expectedReservations.get(0);
        Reservation reservation = entityManager.find(Reservation.class, target.reservationId());
        if (status == ReservationStatus.CANCELLED) {
            reservation.cancelReservation(DAY.atTime(10, 0).atZone(KstTime.ZONE).toInstant());
            ReflectionTestUtils.setField(reservation.getAttractionSlot(), "reservedCount", 0);
        } else {
            reservation.updateReservationStatus(status);
        }
        SearchAdminReservationCondition condition = new SearchAdminReservationCondition(null, null, status, null);

        // when
        Page<SearchAdminReservationResponse> page = queryForRegression(condition, 0, 50);
        Page<SearchAdminReservationResponse> reservedPage = queryForRegression(
                new SearchAdminReservationCondition(null, null, ReservationStatus.RESERVED, null), 0, 50);

        // then
        assertReservationIds(page, List.of(target.reservationId()), 1);
        assertThat(page.getContent().get(0).status()).isEqualTo(status);
        assertThat(reservedPage.getTotalElements()).isEqualTo(49);
        assertThat(reservedPage.getContent()).hasSize(49)
                .allSatisfy(item -> assertThat(item.status()).isEqualTo(ReservationStatus.RESERVED));
        assertThat(reservedPage.getContent()).extracting(SearchAdminReservationResponse::reservationId)
                .doesNotContain(target.reservationId());
    }

    @Test
    @DisplayName("관리자 예약 목록은 슬롯 시작 날짜로 필터링하고 전날과 다음 날 예약을 제외한다")
    void findAdminReservationAll_withDate_excludesPreviousAndNextDay() {
        // given
        User owner = entityManager.find(User.class, expectedReservations.get(0).userId());
        Attraction attraction = entityManager.find(Attraction.class, expectedReservations.get(0).attractionId());
        // 방문일과 슬롯 날짜를 일치시키고 정상 운영 시간 안의 비교 데이터를 만든다.
        persistReservation(owner, createEnteredTicket(owner, DAY.minusDays(1)), attraction,
                DAY.minusDays(1).atTime(11, 0));
        persistReservation(owner, createEnteredTicket(owner, DAY.plusDays(1)), attraction,
                DAY.plusDays(1).atTime(11, 0));
        SearchAdminReservationCondition condition = new SearchAdminReservationCondition(null, null, null, DAY);

        // when
        Page<SearchAdminReservationResponse> page = queryForRegression(condition, 0, 50);

        // then
        assertPage(page, 50);
        assertThat(page.getContent()).allSatisfy(item -> assertThat(item.startsAt().toLocalDate()).isEqualTo(DAY));
    }

    @Test
    @DisplayName("관리자 예약 목록은 회원·어트랙션·상태·날짜 조건을 함께 적용한다")
    void findAdminReservationAll_withCombinedFilters_returnsMatchingReservation() {
        // given
        ExpectedReservation target = expectedReservations.get(0);
        SearchAdminReservationCondition condition = new SearchAdminReservationCondition(
                target.userId(), target.attractionId(), ReservationStatus.RESERVED, DAY);

        // when
        Page<SearchAdminReservationResponse> page = queryForRegression(condition, 0, 10);

        // then
        assertReservationIds(page, List.of(target.reservationId()), 1);
        SearchAdminReservationResponse actual = page.getContent().get(0);
        assertThat(actual.userId()).isEqualTo(target.userId());
        assertThat(actual.attractionId()).isEqualTo(target.attractionId());
        assertThat(actual.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(actual.startsAt().toLocalDate()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("관리자 예약 목록은 조건에 맞는 예약이 없으면 전체 건수 0의 빈 페이지를 반환한다")
    void findAdminReservationAll_withoutMatches_returnsEmptyPage() {
        // given
        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(null, null, null, DAY.plusDays(10));

        // when
        Page<SearchAdminReservationResponse> page = queryForRegression(condition, 0, 10);

        // then
        assertReservationIds(page, List.of(), 0);
        assertThat(page.getTotalPages()).isZero();
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(10);
        assertThat(page.hasNext()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1, 10", "4, 12"})
    @DisplayName("관리자 예약 목록은 두 번째 페이지와 마지막 부분 페이지의 순서·건수·페이지 정보를 유지한다")
    void findAdminReservationAll_withPageOffset_preservesPagination(int pageNumber, int pageSize) {
        // given
        SearchAdminReservationCondition condition = new SearchAdminReservationCondition(null, null, null, null);
        List<Long> expectedIds = expectedReservations.stream().map(ExpectedReservation::reservationId)
                .sorted(Comparator.reverseOrder()).skip((long) pageNumber * pageSize).limit(pageSize).toList();

        // when
        Page<SearchAdminReservationResponse> page = queryForRegression(condition, pageNumber, pageSize);

        // then
        assertReservationIds(page, expectedIds, RESERVATION_COUNT);
        assertThat(page.getNumber()).isEqualTo(pageNumber);
        assertThat(page.getSize()).isEqualTo(pageSize);
        assertThat(page.getTotalPages()).isEqualTo((RESERVATION_COUNT + pageSize - 1) / pageSize);
        assertThat(page.hasPrevious()).isTrue();
        assertThat(page.hasNext()).isEqualTo(pageNumber + 1 < page.getTotalPages());
    }

    private Page<SearchAdminReservationResponse> queryForRegression(
            SearchAdminReservationCondition condition, int pageNumber, int pageSize) {
        // 조건별 검증에서도 fixture와 이전 조회의 1차 캐시·통계 영향을 제외한다.
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        PageRequest pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.desc("status"), Sort.Order.desc("id")));
        Page<SearchAdminReservationResponse> page = reservationService.findAdminReservationAll(condition, pageable);
        assertThat(statistics.getPrepareStatementCount()).as("조건별 조회의 목록·count SELECT 수")
                .isLessThanOrEqualTo(MAX_SELECT_COUNT);
        return page;
    }

    private void assertReservationIds(Page<SearchAdminReservationResponse> page, List<Long> expectedIds, long total) {
        assertThat(page.getTotalElements()).isEqualTo(total);
        assertThat(page.getNumberOfElements()).isEqualTo(expectedIds.size());
        assertThat(page.getContent()).extracting(SearchAdminReservationResponse::reservationId)
                .containsExactlyElementsOf(expectedIds);
    }

    private Measurement measure(int pageSize, SearchAdminReservationCondition condition) {
        // fixture INSERT와 이전 조회의 캐시·통계를 측정에서 제외한다.
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        PageRequest pageable = PageRequest.of(0, pageSize,
                Sort.by(Sort.Order.desc("status"), Sort.Order.desc("id")));

        long startedAt = System.nanoTime();
        Page<SearchAdminReservationResponse> page = reservationService.findAdminReservationAll(condition, pageable);
        long elapsedNanos = System.nanoTime() - startedAt;
        long statementCount = statistics.getPrepareStatementCount();

        // 이 구간은 읽기만 수행하므로 prepared statement 수를 SELECT 수로 해석한다.
        // queryExecutionCount만으로는 지연 로딩 SQL을 모두 셀 수 없어 두 통계를 함께 기록한다.
        // Statistics는 SessionFactory 전체 통계이므로 이 테스트를 단독 실행한다.
        // 단일 durationMs는 참고값이며 부하 테스트 결과나 응답 속도 개선율로 사용하지 않는다.
        System.out.printf(Locale.ROOT,
                "event=ADMIN_RESERVATION_LIST_QUERY_MEASURED pageSize=%d count=%d totalElements=%d "
                        + "statementCount=%d queryExecutionCount=%d entityFetchCount=%d durationMs=%.3f%n",
                pageSize, page.getNumberOfElements(), page.getTotalElements(), statementCount,
                statistics.getQueryExecutionCount(), statistics.getEntityFetchCount(), elapsedNanos / 1_000_000.0);
        return new Measurement(page, statementCount);
    }

    private void assertPage(Page<SearchAdminReservationResponse> page, int pageSize) {
        List<ExpectedReservation> expected = expectedReservations.stream()
                .sorted(Comparator.comparing(ExpectedReservation::reservationId).reversed())
                .limit(pageSize).toList();
        assertThat(page.getTotalElements()).isEqualTo(RESERVATION_COUNT);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(pageSize);
        assertThat(page.getNumberOfElements()).isEqualTo(pageSize);
        assertThat(page.getTotalPages()).isEqualTo((RESERVATION_COUNT + pageSize - 1) / pageSize);
        assertThat(page.hasNext()).isEqualTo(pageSize < RESERVATION_COUNT);
        assertThat(page.getContent()).extracting(SearchAdminReservationResponse::reservationId)
                .containsExactlyElementsOf(expected.stream().map(ExpectedReservation::reservationId).toList());
        assertThat(page.getContent()).extracting(SearchAdminReservationResponse::userId)
                .containsAll(expectedReservations.stream().map(ExpectedReservation::userId).distinct().toList());
        for (int index = 0; index < expected.size(); index++) {
            SearchAdminReservationResponse actual = page.getContent().get(index);
            ExpectedReservation item = expected.get(index);
            assertThat(actual.reservationNumber()).isEqualTo(item.reservationNumber());
            assertThat(actual.userId()).isEqualTo(item.userId());
            assertThat(actual.attractionId()).isEqualTo(item.attractionId());
            assertThat(actual.attractionName()).isEqualTo(item.attractionName());
            assertThat(actual.slotId()).isEqualTo(item.slotId());
            assertThat(actual.startsAt()).isEqualTo(item.startsAt());
            assertThat(actual.endsAt()).isEqualTo(item.endsAt());
            assertThat(actual.status()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(actual.createdAt()).isNotNull();
        }
    }

    private User createUser(String nickname) {
        User user = User.builder().email(UUID.randomUUID() + "@test.com")
                .password("test-password-hash").nickname(nickname).build();
        entityManager.persist(user);
        return user;
    }

    private Ticket createEnteredTicket(User owner) {
        return createEnteredTicket(owner, DAY);
    }

    private Ticket createEnteredTicket(User owner, LocalDate visitDate) {
        TicketProduct product = new TicketProduct(
                "종일권", 10000, "관리자 예약 목록 측정용", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
        entityManager.persist(product);
        Order order = Order.create(owner, 10000, visitDate);
        order.addOrderItem(product, 1);
        order.markAsPaid();
        entityManager.persist(order);
        Ticket ticket = new Ticket(visitDate, order.getOrderItems().get(0));
        ticket.entryTicket(visitDate.atTime(10, 0).atZone(KstTime.ZONE).toInstant());
        entityManager.persist(ticket);
        return ticket;
    }

    private Reservation createReservation(User owner, Ticket ticket, int index) {
        Attraction attraction = Attraction.create(
                "kan129-" + UUID.randomUUID(), "조회 테스트 시설", "A구역", 10, 5);
        entityManager.persist(attraction);
        return persistReservation(owner, ticket, attraction, DAY.atTime(11, 0).plusMinutes(index * 10L));
    }

    private Reservation persistReservation(User owner, Ticket ticket, Attraction attraction, LocalDateTime startsAt) {
        AttractionSlot slot = AttractionSlot.create(attraction, startsAt, 10);
        // 예약 생성 Service 대신 fixture를 직접 저장하므로 예약 수에 맞는 슬롯 인원도 구성한다.
        ReflectionTestUtils.setField(slot, "reservedCount", 1);
        entityManager.persist(slot);
        Reservation reservation = new Reservation(owner, ticket, slot);
        entityManager.persist(reservation);
        return reservation;
    }

    private record ExpectedReservation(Long reservationId, String reservationNumber, Long userId,
                                       Long attractionId, String attractionName, Long slotId,
                                       LocalDateTime startsAt, LocalDateTime endsAt) {
    }

    private record Measurement(Page<SearchAdminReservationResponse> page, long statementCount) {
    }
}
