package com.example.attractionreservation;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.PlannedVisit;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteReservationResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteReservationService;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;

// 추천 경로 일괄 예약이 실제 MySQL 트랜잭션·비관적 잠금에서 "전부 성공 아니면 전부 취소"로 동작하는지 검증한다.
// 예약 생성(ReservationService)은 mock 없이 실제로 실행하고, 업무 시각만 주입 Clock으로 고정한다.
@DataJpaTest(properties = "spring.datasource.hikari.maximum-pool-size=10")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JpaConfig.class,
        QuerydslConfig.class,
        RouteReservationService.class,
        ReservationService.class, SimpleMeterRegistry.class,
        UserService.class,
        TicketService.class,
        AttractionSlotService.class,
        RouteReservationIntegrationTest.MySqlConfig.class
})
class RouteReservationIntegrationTest {

    // 테스트용 현재 시각. 입장권 이용일(오늘)과 슬롯 시각(14:00 이후, 예약 마감 30분 전 이전)의 기준이다.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final int ROOMY_CAPACITY = 10;
    private static final int LAST_SEAT_CAPACITY = 1;

    @Autowired
    private RouteReservationService routeReservationService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // UserService 생성에 필요하지만 예약 생성 경로에서는 호출하지 않는 의존성이다.
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private Clock clock;

    private TransactionTemplate transactionTemplate;
    private Long productId;
    private final List<Member> members = new ArrayList<>();
    private final List<Long> attractionIds = new ArrayList<>();
    private final List<Long> slotIds = new ArrayList<>();
    private final List<Long> recommendationIds = new ArrayList<>();

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
        // 예약 생성의 업무 시각은 모든 작업 스레드에 주입되는 Clock으로 제어한다.
        when(clock.getZone()).thenReturn(KstTime.ZONE);
        when(clock.instant()).thenReturn(NOW.atZone(KstTime.ZONE).toInstant());
        transactionTemplate = new TransactionTemplate(transactionManager);
        productId = transactionTemplate.execute(status -> {
            TicketProduct product = new TicketProduct("종일 이용권", 50000, "일괄 예약 통합 테스트 상품",
                    TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
            entityManager.persist(product);
            entityManager.flush();
            return product.getId();
        });
    }

    // 작업 트랜잭션이 실제로 커밋되므로 이번 테스트가 만든 데이터만 외래 키 순서에 맞춰 지운다.
    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            for (Member member : members) {
                entityManager.createQuery("delete from Reservation r where r.user.id = :userId")
                        .setParameter("userId", member.userId()).executeUpdate();
            }
            for (Long recommendationId : recommendationIds) {
                // 방문·제외 목록은 cascade로 함께 지워진다.
                entityManager.remove(entityManager.find(RouteRecommendation.class, recommendationId));
            }
            entityManager.flush();
            for (Member member : members) {
                entityManager.remove(entityManager.find(Ticket.class, member.ticketId()));
                entityManager.remove(entityManager.find(OrderItem.class, member.orderItemId()));
                entityManager.remove(entityManager.find(Order.class, member.orderId()));
                entityManager.remove(entityManager.find(User.class, member.userId()));
            }
            for (Long slotId : slotIds) {
                entityManager.remove(entityManager.find(AttractionSlot.class, slotId));
            }
            entityManager.flush();
            for (Long attractionId : attractionIds) {
                entityManager.remove(entityManager.find(Attraction.class, attractionId));
            }
            if (productId != null) {
                entityManager.remove(entityManager.find(TicketProduct.class, productId));
            }
        });
    }

    @Test
    @DisplayName("두 번째로 처리하는 슬롯이 이미 가득 차 있으면 409로 실패하고 먼저 만든 예약과 정원 차감도 롤백된다")
    void createRouteReservations_SecondSlotFull_RollsBackFirst() {
        // given
        Member member = createMember();
        Member otherMember = createMember();
        // 어트랙션 ID 오름차순으로 처리하므로 먼저 만든 어트랙션(작은 ID)의 슬롯을 먼저 예약한다.
        Long firstAttractionId = createAttraction("먼저");
        Long fullAttractionId = createAttraction("매진");
        Long firstSlotId = createSlot(firstAttractionId, LocalTime.of(14, 0), ROOMY_CAPACITY);
        Long fullSlotId = createSlot(fullAttractionId, LocalTime.of(15, 0), LAST_SEAT_CAPACITY);
        // 다른 회원이 실제 예약으로 마지막 자리를 가져가 슬롯이 가득 찬다(자동 마감).
        reservationService.createReservation(otherMember.userId(),
                new CreateReservationRequest(otherMember.ticketId(), fullSlotId));
        Long recommendationId = createRecommendation(member, List.of(
                new StopSpec(firstAttractionId, firstSlotId),
                new StopSpec(fullAttractionId, fullSlotId)));

        // when
        RuntimeException failure = attempt(member.userId(), recommendationId);

        // then
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTE_RESERVATION_FAILED);
            assertThat(exception.getErrorCode().getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getMessage()).startsWith("2번째 방문 '" + attractionName(fullAttractionId) + "'");
        });
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(countReserved(member.userId(), List.of(firstSlotId, fullSlotId))).isZero();
            assertThat(entityManager.find(AttractionSlot.class, firstSlotId).getReservedCount()).isZero();
            assertThat(entityManager.find(AttractionSlot.class, fullSlotId).getReservedCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("같은 추천을 동시에 두 번 일괄 예약하면 한 번만 성공하고 슬롯마다 예약은 한 건만 남는다")
    void createRouteReservations_SameRecommendationTwiceConcurrently_OneSucceeds() throws Exception {
        // given
        Member member = createMember();
        Long firstAttractionId = createAttraction("A");
        Long secondAttractionId = createAttraction("B");
        Long firstSlotId = createSlot(firstAttractionId, LocalTime.of(14, 0), ROOMY_CAPACITY);
        Long secondSlotId = createSlot(secondAttractionId, LocalTime.of(15, 0), ROOMY_CAPACITY);
        Long recommendationId = createRecommendation(member, List.of(
                new StopSpec(firstAttractionId, firstSlotId),
                new StopSpec(secondAttractionId, secondSlotId)));

        // when
        List<RuntimeException> results = runConcurrently(List.of(
                new Attempt(member.userId(), recommendationId),
                new Attempt(member.userId(), recommendationId)), Duration.ofSeconds(30));

        // then
        List<RuntimeException> failures = results.stream().filter(Objects::nonNull).toList();
        transactionTemplate.executeWithoutResult(status -> assertSoftly(softly -> {
            softly.assertThat(results.stream().filter(Objects::isNull).count()).as("성공한 일괄 예약 수").isEqualTo(1);
            softly.assertThat(failures).as("실패는 일괄 예약 실패로만 거절된다").hasSize(1)
                    .allSatisfy(RouteReservationIntegrationTest::assertRouteReservationFailed);
            softly.assertThat(countReserved(member.userId(), List.of(firstSlotId))).as("첫 슬롯 예약 수").isEqualTo(1);
            softly.assertThat(countReserved(member.userId(), List.of(secondSlotId))).as("둘째 슬롯 예약 수").isEqualTo(1);
            softly.assertThat(entityManager.find(AttractionSlot.class, firstSlotId).getReservedCount())
                    .as("첫 슬롯 reservedCount").isEqualTo(1);
            softly.assertThat(entityManager.find(AttractionSlot.class, secondSlotId).getReservedCount())
                    .as("둘째 슬롯 reservedCount").isEqualTo(1);
        }));
    }

    @Test
    @DisplayName("두 회원이 정원 1인 슬롯이 든 추천을 동시에 일괄 예약하면 한 명만 성공하고 실패한 회원의 다른 예약도 남지 않는다")
    void createRouteReservations_TwoUsersLastSeat_OnlyOneSucceeds() throws Exception {
        // given
        Member first = createMember();
        Member second = createMember();
        // 여유 슬롯의 어트랙션 ID가 더 작아 먼저 예약된 뒤, 마지막 자리 슬롯에서 실패하면 함께 롤백돼야 한다.
        Long roomyAttractionId = createAttraction("여유");
        Long lastSeatAttractionId = createAttraction("마지막자리");
        Long roomySlotId = createSlot(roomyAttractionId, LocalTime.of(14, 0), ROOMY_CAPACITY);
        Long lastSeatSlotId = createSlot(lastSeatAttractionId, LocalTime.of(15, 0), LAST_SEAT_CAPACITY);
        List<StopSpec> stops = List.of(
                new StopSpec(roomyAttractionId, roomySlotId),
                new StopSpec(lastSeatAttractionId, lastSeatSlotId));
        Long firstRecommendationId = createRecommendation(first, stops);
        Long secondRecommendationId = createRecommendation(second, stops);

        // when
        List<RuntimeException> results = runConcurrently(List.of(
                new Attempt(first.userId(), firstRecommendationId),
                new Attempt(second.userId(), secondRecommendationId)), Duration.ofSeconds(30));

        // then
        Member winner = results.get(0) == null ? first : second;
        Member loser = winner == first ? second : first;
        List<Long> bothSlots = List.of(roomySlotId, lastSeatSlotId);
        transactionTemplate.executeWithoutResult(status -> assertSoftly(softly -> {
            softly.assertThat(results.stream().filter(Objects::isNull).count()).as("성공한 회원 수").isEqualTo(1);
            softly.assertThat(results.stream().filter(Objects::nonNull).toList()).as("실패 사유").hasSize(1)
                    .allSatisfy(RouteReservationIntegrationTest::assertRouteReservationFailed);
            softly.assertThat(countReserved(winner.userId(), bothSlots)).as("성공한 회원의 예약 수").isEqualTo(2);
            softly.assertThat(countReserved(loser.userId(), bothSlots)).as("실패한 회원의 예약 수").isZero();
            softly.assertThat(entityManager.find(AttractionSlot.class, lastSeatSlotId).getReservedCount())
                    .as("마지막 자리 슬롯 reservedCount").isEqualTo(1);
            softly.assertThat(entityManager.find(AttractionSlot.class, roomySlotId).getReservedCount())
                    .as("여유 슬롯 reservedCount (실패한 회원 몫은 롤백)").isEqualTo(1);
        }));
    }

    @Test
    @DisplayName("방문 순서가 엇갈린 두 추천을 동시에 일괄 예약해도 교착 없이 둘 다 10초 안에 성공한다")
    void createRouteReservations_CrossingOrdersConcurrently_NoDeadlock() throws Exception {
        // given
        Member first = createMember();
        Member second = createMember();
        Long attractionA = createAttraction("A");
        Long attractionB = createAttraction("B");
        Long slotA14 = createSlot(attractionA, LocalTime.of(14, 0), ROOMY_CAPACITY);
        Long slotA15 = createSlot(attractionA, LocalTime.of(15, 0), ROOMY_CAPACITY);
        Long slotB14 = createSlot(attractionB, LocalTime.of(14, 0), ROOMY_CAPACITY);
        Long slotB15 = createSlot(attractionB, LocalTime.of(15, 0), ROOMY_CAPACITY);
        // 회원 1은 A → B, 회원 2는 B → A 순서로 방문한다. 방문 순서대로 잠그면 서로의 어트랙션 잠금을 기다릴 수 있다.
        Long firstRecommendationId = createRecommendation(first, List.of(
                new StopSpec(attractionA, slotA14), new StopSpec(attractionB, slotB15)));
        Long secondRecommendationId = createRecommendation(second, List.of(
                new StopSpec(attractionB, slotB14), new StopSpec(attractionA, slotA15)));

        // when
        long startedAt = System.nanoTime();
        List<RuntimeException> results = runConcurrently(List.of(
                new Attempt(first.userId(), firstRecommendationId),
                new Attempt(second.userId(), secondRecommendationId)), Duration.ofSeconds(10));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // then
        assertThat(results).as("교착·잠금 시간 초과 없이 둘 다 성공").containsOnlyNulls();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(countReserved(first.userId(), List.of(slotA14, slotB15))).isEqualTo(2);
            assertThat(countReserved(second.userId(), List.of(slotB14, slotA15))).isEqualTo(2);
        });
    }

    // ===== 테스트 도우미 =====

    private static void assertRouteReservationFailed(RuntimeException failure) {
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTE_RESERVATION_FAILED));
    }

    private RuntimeException attempt(Long userId, Long recommendationId) {
        try {
            CreateRouteReservationResponse response =
                    routeReservationService.createRouteReservations(userId, recommendationId);
            assertThat(response.reservedCount()).isEqualTo(response.reservations().size());
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    // 각 시도는 테스트 트랜잭션을 공유하지 않고 서비스 트랜잭션을 따로 시작한다. 성공은 null, 실패는 예외를 돌려준다.
    private List<RuntimeException> runConcurrently(List<Attempt> attempts, Duration timeout) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(attempts.size());
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RuntimeException>> futures = new ArrayList<>();
        try {
            for (Attempt attempt : attempts) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
                    }
                    return attempt(attempt.userId(), attempt.recommendationId());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("모든 요청의 시작 준비").isTrue();
            start.countDown();
            long deadline = System.nanoTime() + timeout.toNanos();
            List<RuntimeException> results = new ArrayList<>();
            for (Future<RuntimeException> future : futures) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("DB 상태 확인 및 데이터 정리 전 모든 작업 종료").isTrue();
        }
    }

    private long countReserved(Long userId, List<Long> targetSlotIds) {
        return entityManager.createQuery("""
                        select count(r) from Reservation r
                        where r.user.id = :userId
                          and r.attractionSlot.id in :slotIds
                          and r.status = :status
                        """, Long.class)
                .setParameter("userId", userId)
                .setParameter("slotIds", targetSlotIds)
                .setParameter("status", ReservationStatus.RESERVED)
                .getSingleResult();
    }

    // 오늘 입장해 이용 중(IN_USE)인 종일권을 가진 회원
    private Member createMember() {
        Member member = Objects.requireNonNull(transactionTemplate.execute(status -> {
            User user = User.builder()
                    // users.email은 varchar(50)이므로 접두어를 짧게 둔다 (3 + 32 + 9 = 44자)
                    .email("rr-" + UUID.randomUUID().toString().replace("-", "") + "@test.com")
                    .password("test-password")
                    .nickname("일괄예약회원")
                    .build();
            entityManager.persist(user);
            Order order = Order.create(user, 50000, NOW.toLocalDate());
            entityManager.persist(order);
            OrderItem item = OrderItem.create(order, entityManager.find(TicketProduct.class, productId), 1);
            entityManager.persist(item);
            Ticket ticket = new Ticket(NOW.toLocalDate(), item);
            ticket.entryTicket(NOW.withHour(10).atZone(KstTime.ZONE).toInstant());
            entityManager.persist(ticket);
            entityManager.flush();
            return new Member(user.getId(), order.getId(), item.getId(), ticket.getId());
        }));
        members.add(member);
        return member;
    }

    private Long createAttraction(String label) {
        Long attractionId = transactionTemplate.execute(status -> {
            Attraction attraction = Attraction.create(
                    "일괄예약-" + label + "-" + UUID.randomUUID().toString().substring(0, 8),
                    "일괄 예약 통합 테스트", "테스트구역", 30, 5);
            entityManager.persist(attraction);
            entityManager.flush();
            return attraction.getId();
        });
        attractionIds.add(attractionId);
        return attractionId;
    }

    private Long createSlot(Long attractionId, LocalTime startTime, int capacity) {
        Long slotId = transactionTemplate.execute(status -> {
            AttractionSlot slot = AttractionSlot.create(entityManager.find(Attraction.class, attractionId),
                    NOW.toLocalDate().atTime(startTime), capacity);
            entityManager.persist(slot);
            entityManager.flush();
            return slot.getId();
        });
        slotIds.add(slotId);
        return slotId;
    }

    private String attractionName(Long attractionId) {
        return transactionTemplate.execute(status -> entityManager.find(Attraction.class, attractionId).getName());
    }

    // 추천 서비스가 저장하는 것과 같은 형태로 추천을 저장한다. 방문 순서는 목록 순서다.
    private Long createRecommendation(Member member, List<StopSpec> stopSpecs) {
        Long recommendationId = transactionTemplate.execute(status -> {
            List<RouteRecommendationStop> stops = new ArrayList<>();
            for (int i = 0; i < stopSpecs.size(); i++) {
                StopSpec spec = stopSpecs.get(i);
                AttractionSlot slot = entityManager.find(AttractionSlot.class, spec.slotId());
                stops.add(RouteRecommendationStop.from(new PlannedVisit(i + 1, spec.attractionId(),
                        slot.getAttraction().getName(), spec.slotId(), 5, false,
                        slot.getStartsAt().minusMinutes(5), 5, slot.getStartsAt(), slot.getEndsAt(),
                        (int) Duration.between(slot.getStartsAt().atZone(KstTime.ZONE),
                                slot.getEndsAt().atZone(KstTime.ZONE)).toMinutes())));
            }
            RouteRecommendation recommendation = RouteRecommendation.create(member.userId(), member.ticketId(),
                    NOW, NOW.toLocalDate().atTime(22, 0), stops, List.of());
            entityManager.persist(recommendation);
            entityManager.flush();
            return recommendation.getId();
        });
        recommendationIds.add(recommendationId);
        return recommendationId;
    }

    private record Member(Long userId, Long orderId, Long orderItemId, Long ticketId) {
    }

    private record StopSpec(Long attractionId, Long slotId) {
    }

    private record Attempt(Long userId, Long recommendationId) {
    }
}