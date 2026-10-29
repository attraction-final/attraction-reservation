package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
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
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

// 개선 전에는 실패할 수 있는 회귀 테스트다. 중복 조회와 정원 UPDATE는 실제 MySQL에서 실행한다.
@DataJpaTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JpaConfig.class,
        QuerydslConfig.class,
        ReservationService.class,
        UserService.class,
        TicketService.class,
        AttractionSlotService.class,
        ReservationConcurrencyIntegrationTest.MySqlConfig.class
})
class ReservationConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 10;
    private static final int SLOT_CAPACITY = 20;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 14, 0);

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // UserService의 생성에 필요하지만, 예약 생성 경로에서는 호출하지 않는 의존성이다.
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    private TransactionTemplate transactionTemplate;
    private Fixture fixture;

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
        transactionTemplate = new TransactionTemplate(transactionManager);
        // 별도 transaction으로 commit하여 작업 스레드에서도 테스트 데이터를 조회할 수 있게 한다.
        fixture = Objects.requireNonNull(transactionTemplate.execute(status -> {
            User user = User.builder()
                    .email("reservation-concurrency@test.com")
                    .password("test-password")
                    .nickname("동시예약회원")
                    .build();
            entityManager.persist(user);

            TicketProduct product = new TicketProduct(
                    "종일 이용권", 50000, "예약 동시성 테스트 상품",
                    TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
            );
            entityManager.persist(product);
            Order order = Order.create(user, 50000, NOW.toLocalDate());
            entityManager.persist(order);
            OrderItem item = OrderItem.create(order, product, 1);
            entityManager.persist(item);
            Ticket ticket = new Ticket(NOW.toLocalDate(), item);
            ticket.entryTicket(NOW.withHour(10).atZone(KstTime.ZONE).toInstant());
            entityManager.persist(ticket);

            Attraction attraction = Attraction.create("동시성 테스트 어트랙션", "테스트 설명", "A구역", 30, 5);
            entityManager.persist(attraction);
            AttractionSlot slot = AttractionSlot.create(attraction, NOW.withHour(16), SLOT_CAPACITY);
            entityManager.persist(slot);
            entityManager.flush();

            return new Fixture(user.getId(), product.getId(), order.getId(), item.getId(),
                    ticket.getId(), attraction.getId(), slot.getId());
        }));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        // 작업 transaction은 commit되므로 테스트가 실패해도 생성한 데이터만 직접 정리한다.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from Reservation r where r.user.id = :userId")
                    .setParameter("userId", fixture.userId()).executeUpdate();
            entityManager.remove(entityManager.find(Ticket.class, fixture.ticketId()));
            entityManager.remove(entityManager.find(AttractionSlot.class, fixture.slotId()));
            entityManager.remove(entityManager.find(Attraction.class, fixture.attractionId()));
            entityManager.remove(entityManager.find(OrderItem.class, fixture.orderItemId()));
            entityManager.remove(entityManager.find(Order.class, fixture.orderId()));
            entityManager.remove(entityManager.find(TicketProduct.class, fixture.productId()));
            entityManager.remove(entityManager.find(User.class, fixture.userId()));
        });
    }

    @Test
    @DisplayName("같은 회원이 같은 슬롯에 동시에 열 번 예약해도 한 건만 성공하고 정원은 한 자리만 사용한다")
    void createReservation_SameUserAndSlotConcurrently_OnlyOneSucceeds() throws Exception {
        // given
        CreateReservationRequest request = new CreateReservationRequest(fixture.ticketId(), fixture.slotId());

        // when
        List<RuntimeException> results = reserveConcurrently(request);
        long successCount = results.stream().filter(Objects::isNull).count();
        DatabaseState databaseState = Objects.requireNonNull(transactionTemplate.execute(status -> {
            long activeCount = entityManager.createQuery("""
                            select count(r) from Reservation r
                            where r.user.id = :userId
                              and r.attractionSlot.id = :slotId
                              and r.status = :status
                            """, Long.class)
                    .setParameter("userId", fixture.userId())
                    .setParameter("slotId", fixture.slotId())
                    .setParameter("status", ReservationStatus.RESERVED)
                    .getSingleResult();
            AttractionSlot slot = entityManager.find(AttractionSlot.class, fixture.slotId());
            return new DatabaseState(activeCount, slot.getReservedCount());
        }));

        // then
        System.out.printf("동시 예약 결과: 요청=%d, 성공=%d, 활성 예약=%d, reservedCount=%d, 실패=%s%n",
                REQUEST_COUNT, successCount, databaseState.activeCount(), databaseState.reservedCount(),
                results.stream().filter(Objects::nonNull).toList());

        assertSoftly(softly -> {
            softly.assertThat(successCount).as("성공한 요청 수").isEqualTo(1);
            softly.assertThat(databaseState.activeCount()).as("DB에 저장된 활성 예약 수").isEqualTo(1);
            softly.assertThat(databaseState.reservedCount()).as("슬롯 예약 인원").isEqualTo(1);
            softly.assertThat((long) databaseState.reservedCount())
                    .as("슬롯 예약 인원과 실제 활성 예약 수의 일치")
                    .isEqualTo(databaseState.activeCount());
            softly.assertThat(results.stream().filter(Objects::nonNull).toList())
                    .as("실패 요청은 DB 연결 오류 등이 아닌 예약 중복 또는 시간 충돌로 거절되어야 한다")
                    .allSatisfy(failure -> assertThat(failure).isInstanceOfSatisfying(
                            BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isIn(
                                    ErrorCode.RESERVATION_DUPLICATED, ErrorCode.RESERVATION_TIME_CONFLICT
                            )
                    ));
        });
    }

    private List<RuntimeException> reserveConcurrently(CreateReservationRequest request) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RuntimeException>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < REQUEST_COUNT; index++) {
                futures.add(executor.submit(() -> {
                    // static mock은 스레드별로 적용되므로 각 작업에서 같은 기준 시각을 설정한다.
                    try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
                        time.when(KstTime::now).thenReturn(NOW);
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
                        }
                        try {
                            // 테스트 transaction을 공유하지 않고 Service의 transaction을 각각 시작한다.
                            reservationService.createReservation(fixture.userId(), request);
                            return null;
                        } catch (RuntimeException exception) {
                            return exception;
                        }
                    }
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).as("모든 요청의 시작 준비").isTrue();
            start.countDown();

            List<RuntimeException> results = new ArrayList<>();
            for (Future<RuntimeException> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("DB 상태 확인 및 데이터 정리 전 모든 작업 종료").isTrue();
        }
    }

    private record Fixture(Long userId, Long productId, Long orderId, Long orderItemId,
                           Long ticketId, Long attractionId, Long slotId) {
    }

    private record DatabaseState(long activeCount, int reservedCount) {
    }
}
