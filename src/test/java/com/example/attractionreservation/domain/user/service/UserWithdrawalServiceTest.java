package com.example.attractionreservation.domain.user.service;

import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private AttractionSlotRepository attractionSlotRepository;

    private UserWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new UserWithdrawalService(
                userRepository,
                ticketRepository,
                reservationRepository,
                attractionSlotRepository
        );
    }

    @Test
    @DisplayName("미사용 입장권이 있으면 예약과 회원 상태를 변경하지 않는다")
    void withdraw_unusedTicket_rejectedBeforeChanges() {
        User user = activeUser();

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(ticketRepository
                .existsByOrderItem_Order_User_IdAndStatusAndValidDateGreaterThanEqual(
                        eq(1L),
                        eq(TicketStatus.UNUSED),
                        any(LocalDate.class)
                )).thenReturn(true);

        assertThatThrownBy(() -> service.withdraw(1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.USER_WITHDRAWAL_UNUSED_TICKET
                                )
                );

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getWithdrawnAt()).isNull();

        // 미사용 입장권 확인에서 중단하므로 예약·슬롯에는 접근하지 않는다.
        verifyNoInteractions(
                reservationRepository,
                attractionSlotRepository
        );
        verify(userRepository, never()).flush();
    }

    @Test
    @DisplayName("미사용 입장권과 이용 예정 예약이 없으면 탈퇴 처리한다")
    void withdraw_withoutBlockingItems_marksWithdrawn() {
        User user = activeUser();

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(ticketRepository
                .existsByOrderItem_Order_User_IdAndStatusAndValidDateGreaterThanEqual(
                        eq(1L),
                        eq(TicketStatus.UNUSED),
                        any(LocalDate.class)
                )).thenReturn(false);

        when(reservationRepository.findUpcomingForUpdate(
                eq(1L), any(), any()
        )).thenReturn(java.util.List.of());

        service.withdraw(1L);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getWithdrawnAt()).isNotNull();

        verifyNoInteractions(attractionSlotRepository);
        verify(userRepository).flush();
    }

    private User activeUser() {
        return User.builder()
                .email("user@example.com")
                .password("encoded-password")
                .nickname("회원")
                .build();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    @DisplayName("이용 예정 예약 수만큼 정원을 복구한 뒤 탈퇴한다")
    void withdraw_withReservation_cancelsAndRestoresCapacity(
            int reservationCount
    ) {
        User user = activeUser();

        Attraction attraction = mock(Attraction.class);
        when(attraction.getDurationMinutes()).thenReturn(10);

        AttractionSlot slot = AttractionSlot.create(
                attraction,
                LocalDateTime.of(2030, 1, 1, 12, 0),
                2
        );

        // 정원 부족으로 자동 마감된 슬롯을 재현한다.
        ReflectionTestUtils.setField(slot, "id", 10L);
        ReflectionTestUtils.setField(slot, "reservedCount", 2);
        ReflectionTestUtils.setField(slot, "status", SlotStatus.CLOSED);
        ReflectionTestUtils.setField(slot, "autoClosed", true);

        // 모든 예약이 같은 슬롯을 사용하도록 구성한다.
        List<Reservation> reservations = new java.util.ArrayList<>();

        for (int i = 0; i < reservationCount; i++) {
            reservations.add(new Reservation(
                    user,
                    mock(Ticket.class),
                    slot
            ));
        }

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(ticketRepository
                .existsByOrderItem_Order_User_IdAndStatusAndValidDateGreaterThanEqual(
                        eq(1L),
                        eq(TicketStatus.UNUSED),
                        any(LocalDate.class)
                )).thenReturn(false);

        when(reservationRepository.findUpcomingForUpdate(
                eq(1L), eq(ReservationStatus.RESERVED), any()
        )).thenReturn(reservations);

        when(attractionSlotRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(slot));

        service.withdraw(1L);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getWithdrawnAt()).isNotNull();

        // 조회된 모든 예약이 취소됐는지 확인한다.
        for (Reservation reservation : reservations) {
            assertThat(reservation.getStatus())
                    .isEqualTo(ReservationStatus.CANCELLED);
            assertThat(reservation.getCancelReason())
                    .isEqualTo(ReservationCancelReason.USER_WITHDRAWAL);
            assertThat(reservation.getCancelledAt())
                    .isEqualTo(user.getWithdrawnAt());
        }

        // 전체 정원은 유지하고 취소된 예약 수만큼 예약 인원을 줄인다.
        assertThat(slot.getCapacity()).isEqualTo(2);
        assertThat(slot.getReservedCount())
                .isEqualTo(2 - reservationCount);
        assertThat(slot.getRemainingCount())
                .isEqualTo(reservationCount);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(slot.isAutoClosed()).isFalse();

        verify(attractionSlotRepository, times(reservationCount))
                .findByIdForUpdate(10L);
        verify(userRepository).flush();
    }

    @ParameterizedTest
    @EnumSource(
            value = UserStatus.class,
            names = "ACTIVE",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("ACTIVE가 아닌 회원은 탈퇴 처리 전에 거부한다")
    void withdraw_notActive_rejectedBeforeChanges(UserStatus status) {
        User user = activeUser();
        ReflectionTestUtils.setField(user, "status", status);

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.withdraw(1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_WITHDRAWABLE)
                );

        assertThat(user.getStatus()).isEqualTo(status);
        assertThat(user.getWithdrawnAt()).isNull();

        verifyNoInteractions(
                ticketRepository,
                reservationRepository,
                attractionSlotRepository
        );
        verify(userRepository, never()).flush();
    }

    @Test
    @DisplayName("인증된 회원이 DB에 없으면 탈퇴 처리를 진행하지 않는다")
    void withdraw_memberNotFound_rejected() {
        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED)
                );

        verifyNoInteractions(
                ticketRepository,
                reservationRepository,
                attractionSlotRepository
        );
        verify(userRepository, never()).flush();
    }

    @ParameterizedTest
    @EnumSource(
            value = SlotStatus.class,
            names = {"CLOSED", "SUSPENDED", "CANCELLED"}
    )
    @DisplayName("탈퇴로 정원을 복구해도 관리자 지정 슬롯 상태는 유지한다")
    void withdraw_preservesManuallyAssignedSlotStatus(SlotStatus status) {
        User user = activeUser();

        Attraction attraction = mock(Attraction.class);
        when(attraction.getDurationMinutes()).thenReturn(10);

        AttractionSlot slot = AttractionSlot.create(
                attraction,
                LocalDateTime.of(2030, 1, 1, 12, 0),
                2
        );

        ReflectionTestUtils.setField(slot, "id", 10L);
        ReflectionTestUtils.setField(slot, "reservedCount", 1);

        // 관리자에 의한 상태 변경은 자동 마감 표시를 해제한다.
        slot.changeStatus(status);

        Reservation reservation = new Reservation(
                user,
                mock(Ticket.class),
                slot
        );

        when(userRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(user));

        when(reservationRepository.findUpcomingForUpdate(
                eq(1L), eq(ReservationStatus.RESERVED), any()
        )).thenReturn(List.of(reservation));

        when(attractionSlotRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(slot));

        // 모의 저장소의 미사용 입장권 조회 결과는 기본값인 false다.
        service.withdraw(1L);

        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(slot.getReservedCount()).isZero();
        assertThat(slot.getCapacity()).isEqualTo(2);
        assertThat(slot.getStatus()).isEqualTo(status);
        assertThat(slot.isAutoClosed()).isFalse();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }
}