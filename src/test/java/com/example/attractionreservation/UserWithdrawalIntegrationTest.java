package com.example.attractionreservation;

import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserWithdrawalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class UserWithdrawalIntegrationTest {

    @Autowired
    private UserWithdrawalService withdrawalService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("예약과 미사용 입장권이 없으면 탈퇴 상태와 시각을 DB에 저장한다")
    void withdraw_withoutBlockingItems_commitsWithdrawal() {
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("탈퇴테스트")
                        .build()
        );

        Long userId = user.getId();

        withdrawalService.withdraw(userId);

        // 서비스 트랜잭션이 끝난 뒤 DB에서 다시 조회한다.
        User savedUser = userRepository.findById(userId)
                .orElseThrow();

        assertThat(savedUser.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(savedUser.getWithdrawnAt()).isNotNull();

        // 소프트 삭제이므로 회원 행과 기존 정보는 남아 있어야 한다.
        assertThat(savedUser.getEmail()).isEqualTo(user.getEmail());
        assertThat(userRepository.existsById(userId)).isTrue();
    }

    @Test
    @DisplayName("예약 취소·정원 복구·회원 탈퇴가 DB에 함께 저장된다")
    void withdraw_withReservation_commitsAllChanges() {
        WithdrawalFixture fixture =
                createReservationFixture(TicketStatus.IN_USE, 1);

        withdrawalService.withdraw(fixture.userId());

        new TransactionTemplate(transactionManager).executeWithoutResult(
                transaction -> {
                    User user = entityManager.find(
                            User.class, fixture.userId()
                    );
                    Reservation reservation = entityManager.find(
                            Reservation.class, fixture.reservationId()
                    );
                    AttractionSlot slot = entityManager.find(
                            AttractionSlot.class, fixture.slotId()
                    );

                    assertThat(user.getStatus())
                            .isEqualTo(UserStatus.WITHDRAWN);
                    assertThat(user.getWithdrawnAt()).isNotNull();

                    assertThat(reservation.getStatus())
                            .isEqualTo(ReservationStatus.CANCELLED);
                    assertThat(reservation.getCancelReason())
                            .isEqualTo(ReservationCancelReason.USER_WITHDRAWAL);
                    assertThat(reservation.getCancelledAt())
                            .isEqualTo(user.getWithdrawnAt());

                    assertThat(slot.getCapacity()).isEqualTo(1);
                    assertThat(slot.getReservedCount()).isZero();
                    assertThat(slot.getRemainingCount()).isEqualTo(1);
                    assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
                }
        );
    }

    private WithdrawalFixture createReservationFixture(
            TicketStatus ticketStatus,
            int reservedCount
    ) {
        return new TransactionTemplate(transactionManager).execute(
                transaction -> {
                    User user = User.builder()
                            .email(UUID.randomUUID() + "@test.com")
                            .password("test-password-hash")
                            .nickname("예약탈퇴테스트")
                            .build();
                    entityManager.persist(user);

                    TicketProduct product = new TicketProduct(
                            "종일권",
                            10000,
                            "탈퇴 통합 테스트 상품",
                            TicketType.ALL_DAY,
                            100,
                            TicketProductStatus.ON_SALE
                    );
                    entityManager.persist(product);

                    Order order = Order.create(user, 10000);
                    order.addOrderItem(product, 1);
                    entityManager.persist(order);

                    LocalDate visitDate = LocalDate.now(
                            ZoneId.of("Asia/Seoul")
                    ).plusDays(1);

                    Ticket ticket = new Ticket(
                            visitDate,
                            order.getOrderItems().get(0)
                    );

                    // 미사용 티켓이 없는 상태의 예약 취소 흐름도 검증한다.
                    ReflectionTestUtils.setField(
                            ticket, "status", ticketStatus
                    );
                    entityManager.persist(ticket);

                    Attraction attraction = Attraction.create(
                            "관람차", "테스트 시설", "A구역", 10, 5
                    );
                    entityManager.persist(attraction);

                    AttractionSlot slot = AttractionSlot.create(
                            attraction,
                            visitDate.atTime(12, 0),
                            1
                    );
                    ReflectionTestUtils.setField(
                            slot, "reservedCount", reservedCount
                    );
                    ReflectionTestUtils.setField(
                            slot, "status", SlotStatus.CLOSED
                    );
                    ReflectionTestUtils.setField(
                            slot, "autoClosed", true
                    );
                    entityManager.persist(slot);

                    Reservation reservation =
                            new Reservation(user, ticket, slot);
                    entityManager.persist(reservation);
                    entityManager.flush();

                    return new WithdrawalFixture(
                            user.getId(),
                            reservation.getId(),
                            slot.getId()
                    );
                }
        );
    }

    private record WithdrawalFixture(
            Long userId,
            Long reservationId,
            Long slotId
    ) {
    }

    @Test
    @DisplayName("정원 복구 중 오류가 발생하면 예약 취소와 탈퇴를 롤백한다")
    void withdraw_capacityRestoreFailed_rollsBackChanges() {
        // 예약은 있지만 예약 인원이 0인 비정상 데이터를 만들어
        // 예약 취소 후 정원 복구 단계에서 오류를 발생시킨다.
        WithdrawalFixture fixture =
                createReservationFixture(TicketStatus.IN_USE, 0);

        assertThatThrownBy(
                () -> withdrawalService.withdraw(fixture.userId())
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("복구할 예약 인원이 없습니다.");

        new TransactionTemplate(transactionManager).executeWithoutResult(
                transaction -> {
                    User user = entityManager.find(
                            User.class, fixture.userId()
                    );
                    Reservation reservation = entityManager.find(
                            Reservation.class, fixture.reservationId()
                    );
                    AttractionSlot slot = entityManager.find(
                            AttractionSlot.class, fixture.slotId()
                    );

                    // 오류 전에 변경한 예약 상태도 DB에는 반영되면 안 된다.
                    assertThat(reservation.getStatus())
                            .isEqualTo(ReservationStatus.RESERVED);
                    assertThat(reservation.getCancelReason()).isNull();
                    assertThat(reservation.getCancelledAt()).isNull();

                    assertThat(user.getStatus())
                            .isEqualTo(UserStatus.ACTIVE);
                    assertThat(user.getWithdrawnAt()).isNull();

                    assertThat(slot.getReservedCount()).isZero();
                    assertThat(slot.getStatus())
                            .isEqualTo(SlotStatus.CLOSED);
                }
        );
    }

    @Test
    @DisplayName("미사용 입장권이 있으면 예약·슬롯·회원 상태를 변경하지 않는다")
    void withdraw_unusedTicket_keepsAllDataUnchanged() {
        WithdrawalFixture fixture =
                createReservationFixture(TicketStatus.UNUSED, 1);

        assertThatThrownBy(
                () -> withdrawalService.withdraw(fixture.userId())
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.USER_WITHDRAWAL_UNUSED_TICKET)
        );

        new TransactionTemplate(transactionManager).executeWithoutResult(
                transaction -> {
                    User user = entityManager.find(
                            User.class, fixture.userId()
                    );
                    Reservation reservation = entityManager.find(
                            Reservation.class, fixture.reservationId()
                    );
                    AttractionSlot slot = entityManager.find(
                            AttractionSlot.class, fixture.slotId()
                    );

                    assertThat(user.getStatus())
                            .isEqualTo(UserStatus.ACTIVE);
                    assertThat(user.getWithdrawnAt()).isNull();

                    assertThat(reservation.getStatus())
                            .isEqualTo(ReservationStatus.RESERVED);
                    assertThat(reservation.getCancelReason()).isNull();
                    assertThat(reservation.getCancelledAt()).isNull();

                    assertThat(slot.getReservedCount()).isEqualTo(1);
                    assertThat(slot.getRemainingCount()).isZero();
                    assertThat(slot.getStatus())
                            .isEqualTo(SlotStatus.CLOSED);
                }
        );
    }

    @ParameterizedTest(name = "입장권 이용일: 오늘 기준 {0}일")
    @ValueSource(ints = {-1, 0, 1})
    @DisplayName("미사용 입장권의 날짜에 따라 탈퇴 허용 여부를 결정한다")
    void withdraw_unusedTicket_checksValidDateInDatabase(int daysFromToday) {
        LocalDate validDate = LocalDate.now(
                ZoneId.of("Asia/Seoul")
        ).plusDays(daysFromToday);

        Long userId = createUserWithUnusedTicket(validDate);

        if (daysFromToday < 0) {
            // 이용일이 지난 미사용 입장권은 탈퇴를 막지 않는다.
            withdrawalService.withdraw(userId);
        } else {
            // 오늘 또는 미래의 미사용 입장권이 있으면 탈퇴를 거부한다.
            assertThatThrownBy(
                    () -> withdrawalService.withdraw(userId)
            ).isInstanceOfSatisfying(
                    BusinessException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(
                                    ErrorCode.USER_WITHDRAWAL_UNUSED_TICKET
                            )
            );
        }

        // 서비스 처리가 끝난 뒤 새 트랜잭션에서 DB 상태를 확인한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(
                transaction -> {
                    User savedUser = entityManager.find(User.class, userId);

                    if (daysFromToday < 0) {
                        assertThat(savedUser.getStatus())
                                .isEqualTo(UserStatus.WITHDRAWN);
                        assertThat(savedUser.getWithdrawnAt()).isNotNull();
                    } else {
                        assertThat(savedUser.getStatus())
                                .isEqualTo(UserStatus.ACTIVE);
                        assertThat(savedUser.getWithdrawnAt()).isNull();
                    }
                }
        );
    }

    private Long createUserWithUnusedTicket(LocalDate validDate) {
        return new TransactionTemplate(transactionManager).execute(
                transaction -> {
                    User user = User.builder()
                            .email(UUID.randomUUID() + "@test.com")
                            .password("test-password-hash")
                            .nickname("입장권날짜테스트")
                            .build();
                    entityManager.persist(user);

                    TicketProduct product = new TicketProduct(
                            "종일권",
                            10000,
                            "입장권 날짜 조건 검증용 상품",
                            TicketType.ALL_DAY,
                            100,
                            TicketProductStatus.ON_SALE
                    );
                    entityManager.persist(product);

                    Order order = Order.create(user, 10000);
                    order.addOrderItem(product, 1);
                    entityManager.persist(order);

                    // 생성자의 기본 상태인 UNUSED로 실제 DB에 저장한다.
                    Ticket ticket = new Ticket(
                            validDate,
                            order.getOrderItems().get(0)
                    );
                    entityManager.persist(ticket);
                    entityManager.flush();

                    return user.getId();
                }
        );
    }
}