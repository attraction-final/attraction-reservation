package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationCancelRequest;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationCancelResponse;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationStatusRequest;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationStatusResponse;
import com.example.attractionreservation.domain.reservation.dto.UpdateReservationCancelResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.domain.user.service.UserWithdrawalService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

// 개선 전에는 실패할 수 있는 재현 테스트다. 예약 조회·상태 변경·정원 복구는 실제 MySQL에서 실행한다.
@DataJpaTest(properties = "spring.datasource.hikari.maximum-pool-size=10")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JpaConfig.class,
        QuerydslConfig.class,
        ReservationService.class,
        UserService.class,
        UserWithdrawalService.class,
        TicketService.class,
        AttractionSlotService.class,
        ReservationCancellationConcurrencyIntegrationTest.MySqlConfig.class
})
class ReservationCancellationConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 2;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 14, 0);

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private UserWithdrawalService userWithdrawalService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MySQLContainer mysqlContainer;

    // UserService의 생성에 필요하지만 이 테스트의 예약·취소 경로에서는 사용하지 않는다.
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    private TransactionTemplate transactionTemplate;
    private Fixture fixture;
    private int mysqlLogOffset;

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            // 이 테스트 전용 MySQL에 deadlock 진단을 기록한다. 잠금·상태 변경 로직은 그대로 둔다.
            return new MySQLContainer(DockerImageName.parse("mysql:9.6"))
                    .withCommand("--innodb-print-all-deadlocks=ON", "--log-error-verbosity=3");
        }
    }

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            time.when(KstTime::now).thenReturn(NOW);
            // 준비 데이터와 예약 두 건을 commit하여 작업 스레드에서도 조회할 수 있게 한다.
            fixture = Objects.requireNonNull(transactionTemplate.execute(status -> {
                TicketProduct product = new TicketProduct(
                        "종일 이용권", 50000, "취소 동시성 테스트 상품",
                        TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
                );
                entityManager.persist(product);
                Member first = createMember("cancel-concurrency-a@test.com", product);
                Member second = createMember("cancel-concurrency-b@test.com", product);
                Attraction attraction = Attraction.create("취소 동시성 어트랙션", "테스트 설명", "A구역", 30, 5);
                entityManager.persist(attraction);
                AttractionSlot slot = AttractionSlot.create(attraction, NOW.withHour(16), 20);
                entityManager.persist(slot);
                entityManager.flush();

                var reservationA = reservationService.createReservation(first.userId(),
                        new CreateReservationRequest(first.ticketId(), slot.getId()));
                var reservationB = reservationService.createReservation(second.userId(),
                        new CreateReservationRequest(second.ticketId(), slot.getId()));
                return new Fixture(first, second, product.getId(), attraction.getId(), slot.getId(),
                        reservationA.reservationId(), reservationB.reservationId());
            }));
        }
        mysqlLogOffset = mysqlContainer.getLogs().length();
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        // 작업 transaction은 실제로 commit되므로 실패해도 이 테스트가 만든 데이터만 정리한다.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from Reservation r where r.user.id in :userIds")
                    .setParameter("userIds", List.of(fixture.first().userId(), fixture.second().userId()))
                    .executeUpdate();
            for (Member member : List.of(fixture.first(), fixture.second())) {
                entityManager.remove(entityManager.find(Ticket.class, member.ticketId()));
                entityManager.remove(entityManager.find(OrderItem.class, member.orderItemId()));
                entityManager.remove(entityManager.find(Order.class, member.orderId()));
                entityManager.remove(entityManager.find(User.class, member.userId()));
            }
            entityManager.remove(entityManager.find(AttractionSlot.class, fixture.slotId()));
            entityManager.remove(entityManager.find(Attraction.class, fixture.attractionId()));
            entityManager.remove(entityManager.find(TicketProduct.class, fixture.productId()));
        });
    }

    @Test
    @DisplayName("예약 두 건이 있는 슬롯에서 같은 예약을 동시에 취소해도 한 번만 성공하고 다른 예약의 인원을 유지한다")
    void cancelReservation_SameReservationConcurrentlyWithTwoReserved_OnlyOneReleaseSucceeds() throws Exception {
        // given
        DatabaseState initialState = readDatabaseState();
        assertThat(initialState.reservationAStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.reservationBStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.activeCount()).isEqualTo(2);
        assertThat(initialState.reservedCount()).as("중복 복구를 발견하기 위해 초기 인원은 2명").isEqualTo(2);

        // when
        List<CancelResult> results = cancelConcurrently();
        List<UpdateReservationCancelResponse> responses = results.stream()
                .map(CancelResult::response).filter(Objects::nonNull).toList();
        List<RuntimeException> failures = results.stream()
                .map(CancelResult::failure).filter(Objects::nonNull).toList();
        DatabaseState actual = readDatabaseState();

        // then
        System.out.printf(
                "동일 예약 중복 취소 결과: 요청=%d, 성공=%d, 실패=%d, 예약A=%s, 예약B=%s, 활성예약=%d, reservedCount=%d, 성공응답=%s, 실패원인=%s%n",
                REQUEST_COUNT, responses.size(), failures.size(), actual.reservationAStatus(),
                actual.reservationBStatus(), actual.activeCount(), actual.reservedCount(), responses, failures
        );

        assertSoftly(softly -> {
            softly.assertThat(responses).as("취소 성공 응답은 1건만 반환").hasSize(1);
            softly.assertThat(failures).as("중복 취소 요청 1건은 실패").hasSize(1);
            softly.assertThat(actual.reservationAStatus()).as("취소 대상 A의 상태")
                    .isEqualTo(ReservationStatus.CANCELLED);
            softly.assertThat(actual.reservationBStatus()).as("취소하지 않은 예약 B의 상태 유지")
                    .isEqualTo(ReservationStatus.RESERVED);
            softly.assertThat(actual.activeCount()).as("DB에 남은 활성 예약 수").isEqualTo(1);
            softly.assertThat(actual.reservedCount()).as("정원 복구는 한 번만 수행").isEqualTo(1);
            softly.assertThat((long) actual.reservedCount()).as("예약 인원과 활성 예약 수의 일치")
                    .isEqualTo(actual.activeCount());
            softly.assertThat(responses).allSatisfy(response -> {
                assertThat(response.reservationId()).isEqualTo(fixture.reservationAId());
                assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
                assertThat(response.cancelledAt()).isEqualTo(NOW.atZone(KstTime.ZONE).toInstant());
            });
            softly.assertThat(failures).allSatisfy(failure -> assertThat(failure).isInstanceOfSatisfying(
                    BusinessException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS)
            ));
        });
    }

    @Test
    @DisplayName("같은 예약에 사용자 취소와 관리자 강제 취소가 동시에 들어와도 한 번만 성공하고 정원을 한 번만 복구한다")
    void cancelReservation_UserAndAdminConcurrently_OnlyOneReleaseSucceeds() throws Exception {
        // given
        DatabaseState initialState = readDatabaseState();
        assertThat(initialState.reservationAStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.reservationBStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.activeCount()).isEqualTo(2);
        assertThat(initialState.reservedCount()).isEqualTo(2);
        ReservationCancelReason adminReason = ReservationCancelReason.OPERATIONAL_ISSUE;
        UpdateAdminReservationCancelRequest adminRequest = new UpdateAdminReservationCancelRequest(adminReason);

        Callable<MixedCancelResult> userTask = () -> {
            try {
                return new MixedCancelResult("USER", reservationService.cancelReservation(
                        fixture.first().userId(), fixture.reservationAId()), null, null);
            } catch (RuntimeException exception) {
                return new MixedCancelResult("USER", null, null, exception);
            }
        };
        Callable<MixedCancelResult> adminTask = () -> {
            try {
                return new MixedCancelResult("ADMIN", null, reservationService.adminReservationCancel(
                        fixture.reservationAId(), adminRequest), null);
            } catch (RuntimeException exception) {
                return new MixedCancelResult("ADMIN", null, null, exception);
            }
        };

        // when
        List<MixedCancelResult> results = runConcurrently(List.of(userTask, adminTask));
        List<MixedCancelResult> successes = results.stream().filter(result -> result.failure() == null).toList();
        List<RuntimeException> failures = results.stream().map(MixedCancelResult::failure)
                .filter(Objects::nonNull).toList();
        DatabaseState actual = readDatabaseState();

        // then
        System.out.printf(
                "사용자·관리자 동시 취소 결과: 요청=%d, 성공=%d, 실패=%d, 예약A=%s, 예약B=%s, 활성예약=%d, reservedCount=%d, 취소사유=%s, 요청별결과=%s%n",
                results.size(), successes.size(), failures.size(), actual.reservationAStatus(),
                actual.reservationBStatus(), actual.activeCount(), actual.reservedCount(),
                actual.reservationACancelReason(), results
        );

        assertSoftly(softly -> {
            softly.assertThat(successes).as("사용자·관리자 중 취소 성공은 1건만 반환").hasSize(1);
            softly.assertThat(failures).as("나머지 취소 요청 1건은 거절").hasSize(1);
            softly.assertThat(actual.reservationAStatus()).as("취소 대상 A의 상태")
                    .isEqualTo(ReservationStatus.CANCELLED);
            softly.assertThat(actual.reservationBStatus()).as("다른 회원의 예약 B는 유지")
                    .isEqualTo(ReservationStatus.RESERVED);
            softly.assertThat(actual.activeCount()).as("남은 활성 예약은 B 한 건").isEqualTo(1);
            softly.assertThat(actual.reservedCount()).as("정원 복구는 한 번만 수행").isEqualTo(1);
            softly.assertThat((long) actual.reservedCount()).as("예약 인원과 실제 활성 예약 수 일치")
                    .isEqualTo(actual.activeCount());
            softly.assertThat(failures).allSatisfy(failure -> assertThat(failure).isInstanceOfSatisfying(
                    BusinessException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS)
            ));
            softly.assertThat(successes).allSatisfy(result -> {
                if (result.userResponse() != null) {
                    assertThat(result.userResponse().reservationId()).isEqualTo(fixture.reservationAId());
                    assertThat(result.userResponse().status()).isEqualTo(ReservationStatus.CANCELLED);
                    assertThat(result.userResponse().cancelledAt()).isEqualTo(NOW.atZone(KstTime.ZONE).toInstant());
                } else {
                    assertThat(result.adminResponse()).isNotNull();
                    assertThat(result.adminResponse().reservationId()).isEqualTo(fixture.reservationAId());
                    assertThat(result.adminResponse().status()).isEqualTo(ReservationStatus.CANCELLED);
                    assertThat(result.adminResponse().cancelledAt()).isNotNull();
                    assertThat(result.adminResponse().cancelReason()).isEqualTo(adminReason);
                }
            });
            if (successes.size() == 1) {
                ReservationCancelReason expectedReason = successes.get(0).adminResponse() != null ? adminReason : null;
                softly.assertThat(actual.reservationACancelReason()).as("최종 취소 사유는 성공한 요청의 결과와 일치")
                        .isEqualTo(expectedReason);
            }
        });
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"COMPLETED", "NO_SHOW"})
    @DisplayName("슬롯 종료 후 관리자 강제 취소와 완료 또는 노쇼가 경합하면 하나만 성공하고 상태와 인원이 일치한다")
    void updateReservationStatus_AdminCancelAndTerminalStatusConcurrently_OnlyOneTransitionSucceeds(
            ReservationStatus targetStatus
    ) throws Exception {
        // given
        DatabaseState initialState = readDatabaseState();
        assertThat(initialState.reservationAStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.reservationBStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.activeCount()).isEqualTo(2);
        assertThat(initialState.reservedCount()).isEqualTo(2);
        // 슬롯은 16:30 종료다. 두 관리자 요청 모두 동일한 종료 이후 시각을 사용한다.
        Instant afterSlotEnd = NOW.withHour(17).atZone(KstTime.ZONE).toInstant();
        ReservationCancelReason adminReason = ReservationCancelReason.OPERATIONAL_ISSUE;
        UpdateAdminReservationCancelRequest cancelRequest = new UpdateAdminReservationCancelRequest(adminReason);
        UpdateAdminReservationStatusRequest statusRequest = new UpdateAdminReservationStatusRequest(targetStatus);

        Callable<AdminTransitionResult> cancelTask = () -> {
            try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                clock.when(Instant::now).thenReturn(afterSlotEnd);
                try {
                    return new AdminTransitionResult("CANCEL", reservationService.adminReservationCancel(
                            fixture.reservationAId(), cancelRequest), null, null);
                } catch (RuntimeException exception) {
                    return new AdminTransitionResult("CANCEL", null, null, exception);
                }
            }
        };
        Callable<AdminTransitionResult> statusTask = () -> {
            try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                clock.when(Instant::now).thenReturn(afterSlotEnd);
                try {
                    return new AdminTransitionResult("STATUS", null, reservationService.updateReservationStatus(
                            fixture.reservationAId(), statusRequest), null);
                } catch (RuntimeException exception) {
                    return new AdminTransitionResult("STATUS", null, null, exception);
                }
            }
        };

        // when
        List<AdminTransitionResult> results = runConcurrently(List.of(cancelTask, statusTask));
        List<AdminTransitionResult> successes = results.stream().filter(result -> result.failure() == null).toList();
        List<AdminTransitionResult> failures = results.stream().filter(result -> result.failure() != null).toList();
        DatabaseState actual = readDatabaseState();

        // then
        System.out.printf(
                "관리자 취소·상태 변경 경합 결과: 대상상태=%s, 성공=%d, 실패=%d, 예약A=%s, 예약B=%s, 활성예약=%d, reservedCount=%d, 취소사유=%s, 요청별결과=%s%n",
                targetStatus, successes.size(), failures.size(), actual.reservationAStatus(),
                actual.reservationBStatus(), actual.activeCount(), actual.reservedCount(),
                actual.reservationACancelReason(), results
        );

        assertSoftly(softly -> {
            softly.assertThat(successes).as("동일 예약의 상태 전이는 한 번만 성공").hasSize(1);
            softly.assertThat(failures).as("나머지 상태 변경 요청은 거절").hasSize(1);
            softly.assertThat(actual.reservationAStatus()).as("예약 A는 취소 또는 요청한 종료 상태")
                    .isIn(ReservationStatus.CANCELLED, targetStatus);
            softly.assertThat(actual.reservationBStatus()).as("다른 예약 B는 변경하지 않음")
                    .isEqualTo(ReservationStatus.RESERVED);
            softly.assertThat(actual.activeCount()).as("RESERVED로 남는 예약은 B 한 건").isEqualTo(1);

            // 기존 구현 기준: 취소만 정원을 복구하고 완료·노쇼는 예약 인원을 유지한다.
            int expectedCount = actual.reservationAStatus() == ReservationStatus.CANCELLED ? 1 : 2;
            softly.assertThat(actual.reservedCount()).as("최종 상태에 맞는 슬롯 예약 인원")
                    .isEqualTo(expectedCount);

            softly.assertThat(failures).allSatisfy(result -> {
                ErrorCode expectedCode = "CANCEL".equals(result.actor())
                        ? ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS
                        : ErrorCode.RESERVATION_STATUS_CHANGE_NOT_ALLOWED;
                assertThat(result.failure()).isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
            });

            softly.assertThat(successes).allSatisfy(result -> {
                if (result.cancelResponse() != null) {
                    assertThat(result.cancelResponse().reservationId()).isEqualTo(fixture.reservationAId());
                    assertThat(result.cancelResponse().status()).isEqualTo(ReservationStatus.CANCELLED);
                    assertThat(result.cancelResponse().cancelledAt()).isEqualTo(afterSlotEnd);
                    assertThat(result.cancelResponse().cancelReason()).isEqualTo(adminReason);
                } else {
                    assertThat(result.statusResponse()).isNotNull();
                    assertThat(result.statusResponse().reservationId()).isEqualTo(fixture.reservationAId());
                    assertThat(result.statusResponse().status()).isEqualTo(targetStatus);
                }
            });
            if (successes.size() == 1) {
                boolean cancelled = successes.get(0).cancelResponse() != null;
                softly.assertThat(actual.reservationAStatus()).as("DB 최종 상태와 성공 응답 일치")
                        .isEqualTo(cancelled ? ReservationStatus.CANCELLED : targetStatus);
                softly.assertThat(actual.reservationACancelReason()).as("취소가 성공한 경우에만 취소 사유 기록")
                        .isEqualTo(cancelled ? adminReason : null);
                softly.assertThat(actual.reservedCount()).as("취소가 성공한 경우에만 한 자리 복구")
                        .isEqualTo(cancelled ? 1 : 2);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @DisplayName("탈퇴 취소와 사용자 또는 관리자 취소가 경합해도 탈퇴를 완료하고 예약 인원을 한 번만 복구한다")
    void withdraw_UserOrAdminCancelConcurrently_ReleasesCapacityOnce(String cancellationActor) throws Exception {
        // given
        DatabaseState initialState = readDatabaseState();
        assertThat(initialState.reservationAStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.reservationBStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(initialState.activeCount()).isEqualTo(2);
        assertThat(initialState.reservedCount()).isEqualTo(2);
        // A 회원의 티켓은 IN_USE이므로 미사용 티켓에 의한 탈퇴 차단은 발생하지 않는다.
        // 14시는 사용자 취소 마감 전이며 A 예약이 탈퇴 시 이용 예정 예약에 포함되는 시각이다.
        Instant executionTime = NOW.atZone(KstTime.ZONE).toInstant();
        ReservationCancelReason adminReason = ReservationCancelReason.OPERATIONAL_ISSUE;
        UpdateAdminReservationCancelRequest adminRequest = new UpdateAdminReservationCancelRequest(adminReason);

        Callable<WithdrawalRaceResult> withdrawalTask = () -> {
            try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                clock.when(Instant::now).thenReturn(executionTime);
                try {
                    userWithdrawalService.withdraw(fixture.first().userId());
                    return new WithdrawalRaceResult("WITHDRAWAL", null, null, null);
                } catch (RuntimeException exception) {
                    return new WithdrawalRaceResult("WITHDRAWAL", exception, null, null);
                }
            }
        };
        Callable<WithdrawalRaceResult> cancellationTask = () -> {
            try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                clock.when(Instant::now).thenReturn(executionTime);
                try {
                    if ("USER".equals(cancellationActor)) {
                        return new WithdrawalRaceResult("USER", null, reservationService.cancelReservation(
                                fixture.first().userId(), fixture.reservationAId()), null);
                    }
                    return new WithdrawalRaceResult("ADMIN", null, null,
                            reservationService.adminReservationCancel(fixture.reservationAId(), adminRequest));
                } catch (RuntimeException exception) {
                    return new WithdrawalRaceResult(cancellationActor, exception, null, null);
                }
            }
        };

        // when
        List<WithdrawalRaceResult> results = runConcurrently(List.of(withdrawalTask, cancellationTask));
        if (results.stream().anyMatch(result -> result.failure() != null
                && !(result.failure() instanceof BusinessException))) {
            printMySqlDiagnostics(cancellationActor);
        }
        WithdrawalRaceResult withdrawalResult = results.get(0);
        WithdrawalRaceResult cancellationResult = results.get(1);
        DatabaseState actual = readDatabaseState();
        WithdrawalDatabaseState withdrawalState = Objects.requireNonNull(transactionTemplate.execute(status -> {
            User user = entityManager.find(User.class, fixture.first().userId());
            Reservation reservation = entityManager.find(Reservation.class, fixture.reservationAId());
            return new WithdrawalDatabaseState(user.getStatus(), user.getWithdrawnAt(), reservation.getCancelledAt());
        }));

        // then
        long successCount = results.stream().filter(result -> result.failure() == null).count();
        System.out.printf(
                "탈퇴·취소 경합 결과: 취소주체=%s, 성공=%d, 회원상태=%s, 예약A=%s, 예약B=%s, 활성예약=%d, reservedCount=%d, 취소사유=%s, 요청별결과=%s%n",
                cancellationActor, successCount, withdrawalState.userStatus(), actual.reservationAStatus(),
                actual.reservationBStatus(), actual.activeCount(), actual.reservedCount(),
                actual.reservationACancelReason(), results
        );

        assertSoftly(softly -> {
            softly.assertThat(withdrawalResult.failure()).as("취소가 먼저 완료되어도 회원 탈퇴는 정상 완료").isNull();
            softly.assertThat(withdrawalState.userStatus()).as("탈퇴 회원 상태")
                    .isEqualTo(UserStatus.WITHDRAWN);
            softly.assertThat(withdrawalState.withdrawnAt()).as("탈퇴 시각 기록").isEqualTo(executionTime);
            softly.assertThat(actual.reservationAStatus()).as("탈퇴 회원의 예약 A는 취소됨")
                    .isEqualTo(ReservationStatus.CANCELLED);
            softly.assertThat(withdrawalState.cancelledAt()).as("예약 A의 취소 시각 기록").isEqualTo(executionTime);
            softly.assertThat(actual.reservationBStatus()).as("다른 회원의 예약 B는 유지")
                    .isEqualTo(ReservationStatus.RESERVED);
            softly.assertThat(actual.activeCount()).as("남은 활성 예약은 B 한 건").isEqualTo(1);
            softly.assertThat(actual.reservedCount()).as("예약 A 때문에 인원은 한 번만 감소").isEqualTo(1);
            softly.assertThat((long) actual.reservedCount()).as("예약 인원과 활성 예약 수 일치")
                    .isEqualTo(actual.activeCount());

            // 취소가 먼저 성공하면 탈퇴도 성공할 수 있다. 두 성공을 중복 정원 복구로 판단하지 않는다.
            if (cancellationResult.failure() == null) {
                if ("USER".equals(cancellationActor)) {
                    softly.assertThat(cancellationResult.userResponse()).isNotNull();
                    if (cancellationResult.userResponse() != null) {
                        softly.assertThat(cancellationResult.userResponse().reservationId())
                                .isEqualTo(fixture.reservationAId());
                        softly.assertThat(cancellationResult.userResponse().status()).isEqualTo(ReservationStatus.CANCELLED);
                    }
                    softly.assertThat(actual.reservationACancelReason()).as("일반 취소 사유를 탈퇴가 덮어쓰지 않음")
                            .isNull();
                } else {
                    softly.assertThat(cancellationResult.adminResponse()).isNotNull();
                    if (cancellationResult.adminResponse() != null) {
                        softly.assertThat(cancellationResult.adminResponse().reservationId())
                                .isEqualTo(fixture.reservationAId());
                        softly.assertThat(cancellationResult.adminResponse().status()).isEqualTo(ReservationStatus.CANCELLED);
                        softly.assertThat(cancellationResult.adminResponse().cancelReason()).isEqualTo(adminReason);
                    }
                    softly.assertThat(actual.reservationACancelReason()).as("관리자 취소 사유를 탈퇴가 덮어쓰지 않음")
                            .isEqualTo(adminReason);
                }
            } else {
                softly.assertThat(cancellationResult.failure()).isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS));
                softly.assertThat(actual.reservationACancelReason()).as("탈퇴가 먼저 예약을 취소한 경우 사유 유지")
                        .isEqualTo(ReservationCancelReason.USER_WITHDRAWAL);
            }
        });
    }

    private void printMySqlDiagnostics(String cancellationActor) {
        System.out.printf("=== KAN-91 MySQL deadlock 진단 시작: WITHDRAWAL / %s ===%n", cancellationActor);
        try {
            String logs = mysqlContainer.getLogs();
            // 현재 테스트 시작 이후의 로그만 출력해 이전 시나리오 기록과 혼동하지 않도록 한다.
            String currentTestLogs = logs.substring(Math.min(mysqlLogOffset, logs.length()));
            if (currentTestLogs.isBlank()) {
                System.out.println("새 MySQL 서버 로그가 없습니다. deadlock 상세 기록 확보 여부를 확인해야 합니다.");
            } else {
                System.out.println(currentTestLogs);
            }
        } catch (RuntimeException exception) {
            // 진단 수집 오류가 원래 테스트 실패 원인을 가리지 않도록 한다.
            System.out.printf("MySQL 진단 로그 수집 실패: %s%n", exception.getMessage());
        }
        System.out.println("=== KAN-91 MySQL deadlock 진단 끝 ===");
    }

    private Member createMember(String email, TicketProduct product) {
        User user = User.builder().email(email).password("test-password").nickname("취소예약회원").build();
        entityManager.persist(user);
        Order order = Order.create(user, 50000, NOW.toLocalDate());
        entityManager.persist(order);
        OrderItem item = OrderItem.create(order, product, 1);
        entityManager.persist(item);
        Ticket ticket = new Ticket(NOW.toLocalDate(), item);
        ticket.entryTicket(NOW.withHour(10).atZone(KstTime.ZONE).toInstant());
        entityManager.persist(ticket);
        return new Member(user.getId(), order.getId(), item.getId(), ticket.getId());
    }

    private DatabaseState readDatabaseState() {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Reservation first = entityManager.find(Reservation.class, fixture.reservationAId());
            Reservation second = entityManager.find(Reservation.class, fixture.reservationBId());
            AttractionSlot slot = entityManager.find(AttractionSlot.class, fixture.slotId());
            long activeCount = entityManager.createQuery("""
                            select count(r) from Reservation r
                            where r.attractionSlot.id = :slotId and r.status = :status
                            """, Long.class)
                    .setParameter("slotId", fixture.slotId())
                    .setParameter("status", ReservationStatus.RESERVED)
                    .getSingleResult();
            return new DatabaseState(first.getStatus(), second.getStatus(), activeCount, slot.getReservedCount(),
                    first.getCancelReason());
        }));
    }

    private List<CancelResult> cancelConcurrently() throws Exception {
        Callable<CancelResult> task = () -> {
            try {
                return new CancelResult(reservationService.cancelReservation(
                        fixture.first().userId(), fixture.reservationAId()), null);
            } catch (RuntimeException exception) {
                return new CancelResult(null, exception);
            }
        };
        return runConcurrently(Collections.nCopies(REQUEST_COUNT, task));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
                        time.when(KstTime::now).thenReturn(NOW);
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("취소 동시 요청 시작 대기 시간 초과");
                        }
                        // 각 작업에서 별도의 Service transaction을 시작한다.
                        return task.call();
                    }
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).as("모든 취소 요청의 시작 준비").isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("DB 상태 확인 및 데이터 정리 전 모든 취소 작업 종료").isTrue();
        }
    }

    private record Member(Long userId, Long orderId, Long orderItemId, Long ticketId) {
    }

    private record Fixture(Member first, Member second, Long productId, Long attractionId, Long slotId,
                           Long reservationAId, Long reservationBId) {
    }

    private record DatabaseState(ReservationStatus reservationAStatus, ReservationStatus reservationBStatus,
                                 long activeCount, int reservedCount, ReservationCancelReason reservationACancelReason) {
    }

    private record CancelResult(UpdateReservationCancelResponse response, RuntimeException failure) {
    }

    private record MixedCancelResult(String actor, UpdateReservationCancelResponse userResponse,
                                     UpdateAdminReservationCancelResponse adminResponse, RuntimeException failure) {
    }

    private record AdminTransitionResult(String actor, UpdateAdminReservationCancelResponse cancelResponse,
                                         UpdateAdminReservationStatusResponse statusResponse, RuntimeException failure) {
    }

    private record WithdrawalRaceResult(String actor, RuntimeException failure,
                                        UpdateReservationCancelResponse userResponse,
                                        UpdateAdminReservationCancelResponse adminResponse) {
    }

    private record WithdrawalDatabaseState(UserStatus userStatus, Instant withdrawnAt, Instant cancelledAt) {
    }
}
