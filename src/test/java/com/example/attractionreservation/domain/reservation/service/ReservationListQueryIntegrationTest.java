package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.reservation.dto.SearchReservationCondition;
import com.example.attractionreservation.domain.reservation.dto.SearchReservationResponse;
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

// 실제 목록 Service·DTO 변환·MySQL 조회를 측정한다. 생성·취소 Service는 호출하지 않는다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QuerydslConfig.class, ReservationService.class,
        ReservationListQueryIntegrationTest.MySqlConfig.class})
class ReservationListQueryIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    private static final int RESERVATION_COUNT = 50;
    // 현재 Page 조회 계약의 목록 SELECT와 전체 건수 SELECT를 합친 개선 목표다.
    private static final long MAX_SELECT_COUNT = 2;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private EntityManager entityManager;

    // 목록 조회가 사용하지 않는 협력 Service만 mock 처리한다. Repository와 DTO 변환은 실제 실행한다.
    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private AttractionSlotService attractionSlotService;

    private Statistics statistics;
    private Long ownerId;
    private Long otherReservationId;
    private final List<ExpectedReservation> expectedReservations = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            return new MySQLContainer(DockerImageName.parse("mysql:9.6"));
        }
    }

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        User owner = createUser("목록조회회원");
        ownerId = owner.getId();
        Ticket ownerTicket = createEnteredTicket(owner);

        // 각 예약이 서로 다른 슬롯·어트랙션을 사용해야 1차 캐시가 N+1을 가리지 않는다.
        // 한 회원의 탑승 시간도 서로 겹치지 않도록 10분 간격으로 구성한다.
        for (int index = 0; index < RESERVATION_COUNT; index++) {
            Reservation reservation = createReservation(owner, ownerTicket, index);
            AttractionSlot slot = reservation.getAttractionSlot();
            expectedReservations.add(new ExpectedReservation(reservation.getId(), ownerTicket.getId(),
                    slot.getId(), slot.getAttraction().getName(), slot.getStartsAt(), slot.getEndsAt()));
        }

        // 타인의 예약을 더 큰 ID로 추가해 잘못된 소유자 필터도 검출한다.
        User other = createUser("다른목록회원");
        otherReservationId = createReservation(other, createEnteredTicket(other), 50).getId();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("내 예약 목록은 DTO 변환까지 10건과 50건 모두 추가 연관 조회 없이 일정한 SQL 수를 유지한다")
    void findReservationAll_differentPageSizes_keepsSelectCountConstant() {
        // given
        SearchReservationCondition condition = new SearchReservationCondition(ReservationStatus.RESERVED, DAY);

        // when
        Measurement smallPage = measure(10, condition);
        Measurement largePage = measure(50, condition);

        // then
        assertPage(smallPage.page(), 10);
        assertPage(largePage.page(), 50);
        assertSoftly(softly -> {
            softly.assertThat(smallPage.statementCount()).as("10건: 목록·count를 포함한 SELECT 수")
                    .isLessThanOrEqualTo(MAX_SELECT_COUNT);
            softly.assertThat(largePage.statementCount()).as("50건: 목록·count를 포함한 SELECT 수")
                    .isLessThanOrEqualTo(MAX_SELECT_COUNT);
            softly.assertThat(largePage.statementCount()).as("조회 건수가 늘어도 SELECT 수는 증가하지 않음")
                    .isEqualTo(smallPage.statementCount());
        });
    }

    private Measurement measure(int pageSize, SearchReservationCondition condition) {
        // fixture INSERT와 이전 측정의 엔티티·통계는 측정에서 제외한다.
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        PageRequest pageable = PageRequest.of(0, pageSize,
                Sort.by(Sort.Order.desc("status"), Sort.Order.desc("id")));

        long startedAt = System.nanoTime();
        Page<SearchReservationResponse> page = reservationService.findReservationAll(ownerId, pageable, condition);
        long elapsedNanos = System.nanoTime() - startedAt;
        long statementCount = statistics.getPrepareStatementCount();

        // 읽기 경로만 측정하므로 prepared statement 수를 SELECT 수로 사용한다.
        // queryExecutionCount는 JPQL 등 쿼리 실행 통계, entityFetchCount는 지연 엔티티 fetch 통계다.
        // 단일 실행 시간은 참고값이며 부하 테스트의 응답 시간이나 개선 효과로 단정하지 않는다.
        System.out.printf(Locale.ROOT,
                "event=RESERVATION_LIST_QUERY_MEASURED pageSize=%d count=%d totalElements=%d "
                        + "statementCount=%d queryExecutionCount=%d entityFetchCount=%d durationMs=%.3f%n",
                pageSize, page.getNumberOfElements(), page.getTotalElements(), statementCount,
                statistics.getQueryExecutionCount(), statistics.getEntityFetchCount(), elapsedNanos / 1_000_000.0);
        return new Measurement(page, statementCount);
    }

    private void assertPage(Page<SearchReservationResponse> page, int pageSize) {
        List<ExpectedReservation> expected = expectedReservations.stream()
                .sorted(Comparator.comparing(ExpectedReservation::reservationId).reversed())
                .limit(pageSize).toList();
        assertThat(page.getTotalElements()).isEqualTo(RESERVATION_COUNT);
        assertThat(page.getSize()).isEqualTo(pageSize);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getTotalPages()).isEqualTo((RESERVATION_COUNT + pageSize - 1) / pageSize);
        assertThat(page.hasNext()).isEqualTo(pageSize < RESERVATION_COUNT);
        assertThat(page.getContent()).extracting(SearchReservationResponse::reservationId)
                .containsExactlyElementsOf(expected.stream().map(ExpectedReservation::reservationId).toList())
                .doesNotContain(otherReservationId);
        for (int index = 0; index < pageSize; index++) {
            SearchReservationResponse actual = page.getContent().get(index);
            ExpectedReservation item = expected.get(index);
            assertThat(actual.slotId()).isEqualTo(item.slotId());
            assertThat(actual.ticketId()).isEqualTo(item.ticketId());
            assertThat(actual.attractionName()).isEqualTo(item.attractionName());
            assertThat(actual.startsAt()).isEqualTo(item.startsAt());
            assertThat(actual.endsAt()).isEqualTo(item.endsAt());
            assertThat(actual.status()).isEqualTo(ReservationStatus.RESERVED);
        }
    }

    private User createUser(String nickname) {
        User user = User.builder().email(UUID.randomUUID() + "@test.com")
                .password("test-password-hash").nickname(nickname).build();
        entityManager.persist(user);
        return user;
    }

    private Ticket createEnteredTicket(User owner) {
        TicketProduct product = new TicketProduct(
                "종일권", 10000, "예약 목록 조회 측정용", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
        entityManager.persist(product);
        Order order = Order.create(owner, 10000, DAY);
        order.addOrderItem(product, 1);
        order.markAsPaid();
        entityManager.persist(order);
        Ticket ticket = new Ticket(DAY, order.getOrderItems().get(0));
        ticket.entryTicket(DAY.atTime(10, 0).atZone(KstTime.ZONE).toInstant());
        entityManager.persist(ticket);
        return ticket;
    }

    private Reservation createReservation(User owner, Ticket ticket, int index) {
        Attraction attraction = Attraction.create(
                "kan126-" + UUID.randomUUID(), "조회 테스트 시설", "A구역", 10, 5);
        entityManager.persist(attraction);
        AttractionSlot slot = AttractionSlot.create(attraction, DAY.atTime(11, 0).plusMinutes(index * 10L), 10);
        // 생성 Service를 호출하지 않는 fixture이므로 예약 1건에 맞는 인원을 직접 구성한다.
        ReflectionTestUtils.setField(slot, "reservedCount", 1);
        entityManager.persist(slot);
        Reservation reservation = new Reservation(owner, ticket, slot);
        entityManager.persist(reservation);
        return reservation;
    }

    private record ExpectedReservation(Long reservationId, Long ticketId, Long slotId,
                                       String attractionName, LocalDateTime startsAt, LocalDateTime endsAt) {
    }

    private record Measurement(Page<SearchReservationResponse> page, long statementCount) {
    }
}
