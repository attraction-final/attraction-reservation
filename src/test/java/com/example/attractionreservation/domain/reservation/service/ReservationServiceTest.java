package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TICKET_ID = 10L;
    private static final Long SLOT_ID = 20L;

    private static final LocalDate VISIT_DATE =
            LocalDate.of(2026, 10, 23);

    private static final LocalDateTime NOW =
            VISIT_DATE.atTime(14, 0);

    private static final LocalDateTime STARTS_AT =
            VISIT_DATE.atTime(16, 0);

    private static final LocalDateTime ENDS_AT =
            VISIT_DATE.atTime(16, 10);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserService userService;

    @Mock
    private TicketService ticketService;

    @Mock
    private AttractionSlotService attractionSlotService;

    @InjectMocks
    private ReservationService reservationService;

    private User user;
    private Ticket ticket;
    private AttractionSlot slot;
    private Attraction attraction;
    private CreateReservationRequest request;

    @BeforeEach
    void setUp() {
        user = mock(User.class);
        ticket = mock(Ticket.class);
        slot = mock(AttractionSlot.class);
        attraction = mock(Attraction.class);

        OrderItem orderItem = mock(OrderItem.class);
        Order order = mock(Order.class);

        request = new CreateReservationRequest(TICKET_ID, SLOT_ID);

        // 검증 실패 시 이후 단계의 설정을 사용하지 않으므로
        // 공통 정상 조건에 한해 lenient를 적용한다.
        lenient().when(userService.findByUserId(USER_ID))
                .thenReturn(user);
        lenient().when(user.getId()).thenReturn(USER_ID);
        lenient().when(user.getStatus()).thenReturn(UserStatus.ACTIVE);

        lenient().when(ticketService.findByTicketId(TICKET_ID))
                .thenReturn(ticket);
        lenient().when(ticket.getId()).thenReturn(TICKET_ID);
        lenient().when(ticket.getOrderItem()).thenReturn(orderItem);
        lenient().when(orderItem.getOrder()).thenReturn(order);
        lenient().when(order.getUser()).thenReturn(user);
        lenient().when(ticket.getStatus()).thenReturn(TicketStatus.IN_USE);
        lenient().when(ticket.getValidDate()).thenReturn(VISIT_DATE);
        lenient().when(ticket.getType()).thenReturn(TicketType.ALL_DAY);

        lenient().when(attractionSlotService.findAttractionSlotById(SLOT_ID))
                .thenReturn(slot);
        lenient().when(slot.getId()).thenReturn(SLOT_ID);
        lenient().when(slot.getStartsAt()).thenReturn(STARTS_AT);
        lenient().when(slot.getEndsAt()).thenReturn(ENDS_AT);
        lenient().when(slot.getStatus()).thenReturn(SlotStatus.OPEN);
        lenient().when(slot.getAttraction()).thenReturn(attraction);
        lenient().when(attraction.isOperating()).thenReturn(true);
        lenient().when(attraction.getName()).thenReturn("롤러코스터");
    }

    @Test
    @DisplayName("정상 요청이면 자리를 확보하고 예약을 생성한다")
    void createReservation_success() {
        // given
        stubReservationSave();

        // when
        CreateReservationResponse response = createReservationAt(NOW);

        // then
        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.reservationNumber()).isNotBlank();
        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
        assertThat(response.slotId()).isEqualTo(SLOT_ID);
        assertThat(response.attractionName()).isEqualTo("롤러코스터");
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.startsAt()).isEqualTo(STARTS_AT);
        assertThat(response.endsAt()).isEqualTo(ENDS_AT);

        ArgumentCaptor<Reservation> captor =
                ArgumentCaptor.forClass(Reservation.class);

        InOrder inOrder = inOrder(
                attractionSlotService,
                reservationRepository
        );

        inOrder.verify(attractionSlotService)
                .tryReservationCapacity(SLOT_ID);
        inOrder.verify(reservationRepository).save(captor.capture());

        Reservation savedReservation = captor.getValue();

        assertThat(savedReservation.getUser()).isSameAs(user);
        assertThat(savedReservation.getTicket()).isSameAs(ticket);
        assertThat(savedReservation.getAttractionSlot()).isSameAs(slot);
        assertThat(savedReservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @ParameterizedTest
    @EnumSource(
            value = UserStatus.class,
            names = "ACTIVE",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("활성 상태가 아닌 회원은 예약할 수 없다")
    void createReservation_inactiveUser(UserStatus status) {
        // given
        when(user.getStatus()).thenReturn(status);

        // when & then
        assertReservationRejected(NOW, ErrorCode.USER_NOT_ACTIVE);

        verifyNoInteractions(ticketService, attractionSlotService);
    }

    @Test
    @DisplayName("다른 회원의 티켓으로 예약하면 거절한다")
    void createReservation_ticketAccessDenied() {
        // given
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(2L);
        when(ticket.getOrderItem().getOrder().getUser())
                .thenReturn(owner);

        // when & then
        assertReservationRejected(NOW, ErrorCode.TICKET_ACCESS_DENIED);

        verifyNoInteractions(attractionSlotService);
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketStatus.class,
            names = "IN_USE",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("사용 중 상태가 아닌 티켓은 예약할 수 없다")
    void createReservation_invalidTicketStatus(TicketStatus status) {
        // given
        when(ticket.getStatus()).thenReturn(status);

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.TICKET_RESERVATION_NOT_ALLOWED_STATUS
        );
    }

    @Test
    @DisplayName("티켓 방문일이 오늘이 아니면 예약을 거절한다")
    void createReservation_ticketDateMismatch() {
        // given
        when(ticket.getValidDate())
                .thenReturn(VISIT_DATE.minusDays(1));

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.TICKET_ENTRY_DATE_MISMATCH
        );
    }

    @Test
    @DisplayName("슬롯 날짜와 티켓 방문일이 다르면 예약을 거절한다")
    void createReservation_slotDateMismatch() {
        // given
        when(slot.getStartsAt()).thenReturn(STARTS_AT.plusDays(1));
        when(slot.getEndsAt()).thenReturn(ENDS_AT.plusDays(1));

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.TICKET_RESERVATION_DATE_MISMATCH
        );
    }

    @Test
    @DisplayName("오후권으로 15시 이전 슬롯을 예약하면 거절한다")
    void createReservation_beforeAfternoonStart() {
        // given
        when(ticket.getType()).thenReturn(TicketType.AFTERNOON);
        when(slot.getStartsAt())
                .thenReturn(VISIT_DATE.atTime(14, 50));
        when(slot.getEndsAt())
                .thenReturn(VISIT_DATE.atTime(15, 0));

        // when & then
        assertReservationRejected(
                VISIT_DATE.atTime(13, 0),
                ErrorCode.TICKET_RESERVATION_TIME_NOT_ALLOWED
        );
    }

    @Test
    @DisplayName("오후권으로 15시 정각에 시작하는 슬롯을 예약할 수 있다")
    void createReservation_atAfternoonStart() {
        // given
        when(ticket.getType()).thenReturn(TicketType.AFTERNOON);
        when(slot.getStartsAt())
                .thenReturn(VISIT_DATE.atTime(15, 0));
        when(slot.getEndsAt())
                .thenReturn(VISIT_DATE.atTime(15, 10));
        stubReservationSave();

        // when
        CreateReservationResponse response = createReservationAt(
                VISIT_DATE.atTime(13, 0)
        );

        // then
        assertThat(response.startsAt())
                .isEqualTo(VISIT_DATE.atTime(15, 0));
        verify(attractionSlotService).tryReservationCapacity(SLOT_ID);
    }

    @Test
    @DisplayName("폐장 시간을 넘겨 종료하는 슬롯은 예약할 수 없다")
    void createReservation_endsAfterClosingTime() {
        // given
        when(slot.getStartsAt())
                .thenReturn(VISIT_DATE.atTime(21, 50));
        when(slot.getEndsAt())
                .thenReturn(VISIT_DATE.atTime(22, 10));

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.TICKET_RESERVATION_TIME_NOT_ALLOWED
        );
    }

    @Test
    @DisplayName("폐장 정각에 종료하는 슬롯은 예약할 수 있다")
    void createReservation_endsAtClosingTime() {
        // given
        when(slot.getStartsAt())
                .thenReturn(VISIT_DATE.atTime(21, 50));
        when(slot.getEndsAt())
                .thenReturn(VISIT_DATE.atTime(22, 0));
        stubReservationSave();

        // when
        CreateReservationResponse response = createReservationAt(NOW);

        // then
        assertThat(response.endsAt())
                .isEqualTo(VISIT_DATE.atTime(22, 0));
        verify(attractionSlotService).tryReservationCapacity(SLOT_ID);
    }

    @Test
    @DisplayName("운영 중이 아닌 어트랙션은 예약할 수 없다")
    void createReservation_attractionNotOperating() {
        // given
        when(attraction.isOperating()).thenReturn(false);

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.ATTRACTION_NOT_OPERATING
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = SlotStatus.class,
            names = "OPEN",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("OPEN 상태가 아닌 슬롯은 예약할 수 없다")
    void createReservation_invalidSlotStatus(SlotStatus status) {
        // given
        when(slot.getStatus()).thenReturn(status);

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.SLOT_RESERVATION_NOT_ALLOWED_STATUS
        );
    }

    @Test
    @DisplayName("예약 마감 정각에는 예약할 수 있다")
    void createReservation_atDeadline() {
        // given
        LocalDateTime deadline = STARTS_AT.minusMinutes(30);
        stubReservationSave();

        // when
        CreateReservationResponse response =
                createReservationAt(deadline);

        // then
        assertThat(response.status())
                .isEqualTo(ReservationStatus.RESERVED);
        verify(attractionSlotService).tryReservationCapacity(SLOT_ID);
    }

    @Test
    @DisplayName("예약 마감 정각을 지나면 예약을 거절한다")
    void createReservation_afterDeadline() {
        // given
        LocalDateTime afterDeadline =
                STARTS_AT.minusMinutes(30).plusNanos(1);

        // when & then
        assertReservationRejected(
                afterDeadline,
                ErrorCode.RESERVATION_DEADLINE_PASSED
        );
    }

    @Test
    @DisplayName("동일 슬롯의 활성 예약이 있으면 중복 예약을 거절한다")
    void createReservation_duplicated() {
        // given
        when(reservationRepository.existsReservedByUser_IdAndSlot_Id(
                USER_ID,
                SLOT_ID,
                ReservationStatus.RESERVED
        )).thenReturn(true);

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.RESERVATION_DUPLICATED
        );

        verify(reservationRepository, never())
                .existsTimeConflictReservation(
                        any(), any(), any(), any()
                );
    }

    @Test
    @DisplayName("다른 활성 예약과 탑승 시간이 겹치면 예약을 거절한다")
    void createReservation_timeConflict() {
        // given
        when(reservationRepository.existsTimeConflictReservation(
                USER_ID,
                STARTS_AT,
                ENDS_AT,
                ReservationStatus.RESERVED
        )).thenReturn(true);

        // when & then
        assertReservationRejected(
                NOW,
                ErrorCode.RESERVATION_TIME_CONFLICT
        );
    }

    @Test
    @DisplayName("자리 확보에 실패하면 예약을 저장하지 않는다")
    void createReservation_capacityAcquisitionFailed() {
        // given
        doThrow(new BusinessException(
                ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED
        )).when(attractionSlotService)
                .tryReservationCapacity(SLOT_ID);

        // when & then
        assertThatThrownBy(() -> createReservationAt(NOW))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED
                                )
                );

        verify(reservationRepository, never())
                .save(any(Reservation.class));
    }

    private void stubReservationSave() {
        when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> {
                    Reservation reservation = invocation.getArgument(0);
                    ReflectionTestUtils.setField(
                            reservation,
                            "id",
                            100L
                    );
                    return reservation;
                });
    }

    private CreateReservationResponse createReservationAt(
            LocalDateTime now
    ) {
        try (MockedStatic<KstTime> mocked = mockStatic(KstTime.class)) {
            mocked.when(() ->
                    KstTime.toLocalDateTime(any(Instant.class))
            ).thenReturn(now);

            return reservationService.createReservation(USER_ID, request);
        }
    }

    private void assertReservationRejected(
            LocalDateTime now,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(() -> createReservationAt(now))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(errorCode)
                );

        verify(attractionSlotService, never())
                .tryReservationCapacity(any());
        verify(reservationRepository, never())
                .save(any(Reservation.class));
    }
}