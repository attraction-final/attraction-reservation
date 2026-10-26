package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationCancelRequest;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationCancelResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminReservationCancelServiceTest {

    private static final Long RESERVATION_ID = 100L;
    private static final Long SLOT_ID = 20L;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private AttractionSlotService attractionSlotService;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("관리자 강제 취소 시 사유를 기록하고 슬롯 정원을 복구한다")
    void adminReservationCancel_success() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getId()).thenReturn(SLOT_ID);

        Reservation reservation = createReservation(slot);
        ReservationCancelReason reason =
                ReservationCancelReason.ATTRACTION_MAINTENANCE;

        UpdateAdminReservationCancelRequest request =
                new UpdateAdminReservationCancelRequest(reason);

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        Instant before = Instant.now();

        // when
        UpdateAdminReservationCancelResponse response =
                reservationService.adminReservationCancel(
                        RESERVATION_ID,
                        request
                );

        Instant after = Instant.now();

        // then
        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelReason()).isEqualTo(reason);
        assertThat(reservation.getCancelledAt()).isBetween(before, after);

        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.cancelledAt())
                .isEqualTo(reservation.getCancelledAt());
        assertThat(response.cancelReason()).isEqualTo(reason);
        assertThat(response.cancelReasonMessage())
                .isEqualTo(reason.getMessage());

        verify(attractionSlotService)
                .releaseReservationCapacity(SLOT_ID);
    }

    @Test
    @DisplayName("존재하지 않는 예약이면 강제 취소를 거절한다")
    void adminReservationCancel_notFound() {
        // given
        UpdateAdminReservationCancelRequest request =
                new UpdateAdminReservationCancelRequest(
                        ReservationCancelReason.WEATHER_CONDITION
                );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reservationService.adminReservationCancel(
                        RESERVATION_ID,
                        request
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND_RESERVATION)
        );

        verifyNoInteractions(attractionSlotService);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = "RESERVED",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("예약 완료 상태가 아니면 정원을 복구하지 않는다")
    void adminReservationCancel_invalidStatus(ReservationStatus status) {
        // given
        Reservation reservation = createReservation(
                mock(AttractionSlot.class)
        );
        ReflectionTestUtils.setField(reservation, "status", status);

        UpdateAdminReservationCancelRequest request =
                new UpdateAdminReservationCancelRequest(
                        ReservationCancelReason.WEATHER_CONDITION
                );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() ->
                reservationService.adminReservationCancel(
                        RESERVATION_ID,
                        request
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS
                        )
        );

        assertThat(reservation.getStatus()).isEqualTo(status);
        verifyNoInteractions(attractionSlotService);
    }

    @Test
    @DisplayName("취소 사유가 없으면 정원을 복구하지 않는다")
    void adminReservationCancel_nullReason() {
        // given
        Reservation reservation = createReservation(
                mock(AttractionSlot.class)
        );

        UpdateAdminReservationCancelRequest request =
                new UpdateAdminReservationCancelRequest(null);

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() ->
                reservationService.adminReservationCancel(
                        RESERVATION_ID,
                        request
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.INVALID_RESERVATION_CANCEL_REASON
                        )
        );

        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getCancelledAt()).isNull();
        verifyNoInteractions(attractionSlotService);
    }

    @Test
    @DisplayName("정원 복구 실패 시 예외를 전달한다")
    void adminReservationCancel_capacityRestoreFailed() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getId()).thenReturn(SLOT_ID);

        Reservation reservation = createReservation(slot);

        UpdateAdminReservationCancelRequest request =
                new UpdateAdminReservationCancelRequest(
                        ReservationCancelReason.WEATHER_CONDITION
                );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        doThrow(new BusinessException(
                ErrorCode.RESERVATION_CAPACITY_RESTORE_FAILED
        )).when(attractionSlotService)
                .releaseReservationCapacity(SLOT_ID);

        // when & then
        assertThatThrownBy(() ->
                reservationService.adminReservationCancel(
                        RESERVATION_ID,
                        request
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.RESERVATION_CAPACITY_RESTORE_FAILED
                        )
        );

        verify(attractionSlotService)
                .releaseReservationCapacity(SLOT_ID);
    }

    private Reservation createReservation(AttractionSlot slot) {
        Reservation reservation = new Reservation(
                mock(User.class),
                mock(Ticket.class),
                slot
        );

        ReflectionTestUtils.setField(
                reservation,
                "id",
                RESERVATION_ID
        );

        return reservation;
    }
}