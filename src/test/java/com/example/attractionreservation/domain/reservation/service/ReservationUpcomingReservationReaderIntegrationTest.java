package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusRequest;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.service.AttractionService;
import com.example.attractionreservation.domain.attraction.service.UpcomingReservationReader;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

@DataJpaTest
@ActiveProfiles("test")
@Import({
        JpaConfig.class,
        QuerydslConfig.class,
        ReservationUpcomingReservationReader.class,
        AttractionService.class
})
class ReservationUpcomingReservationReaderIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 14, 0);

    @Autowired
    private UpcomingReservationReader upcomingReservationReader;

    @Autowired
    private AttractionService attractionService;

    @Autowired
    private EntityManager entityManager;

    // 추가: @DataJpaTest 에는 Redis 빈이 없으므로, 캐시 삭제 도우미는 가짜로 넣어 준다
    @MockitoBean
    private AttractionDetailCacheEvictor attractionDetailCacheEvictor;

    private User user;
    private Ticket ticket;
    private Attraction attraction;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .email("upcoming-reader@test.com")
                .password("test-password")
                .nickname("예약회원")
                .build();
        entityManager.persist(user);

        TicketProduct product = new TicketProduct(
                "종일 이용권", 50000, "테스트 상품", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        entityManager.persist(product);
        Order order = Order.create(user, 50000, NOW.toLocalDate());
        entityManager.persist(order);
        OrderItem item = OrderItem.create(order, product, 1);
        entityManager.persist(item);
        ticket = new Ticket(NOW.toLocalDate(), item);
        entityManager.persist(ticket);

        attraction = Attraction.create("롤러코스터", "테스트 설명", "A구역", 10, 5);
        entityManager.persist(attraction);
    }

    @Test
    @DisplayName("실제 Reader는 전달받은 어트랙션과 시각을 기준으로 미래 RESERVED 예약만 집계한다")
    void countUpcomingReservationsByAttractionId_RealRepository_ReturnsFilteredCount() {
        // given
        createReservation(attraction, NOW.plusHours(1), ReservationStatus.RESERVED);
        createReservation(attraction, NOW.plusHours(2), ReservationStatus.RESERVED);
        createReservation(attraction, NOW.plusHours(1), ReservationStatus.CANCELLED);
        createReservation(attraction, NOW.minusHours(1), ReservationStatus.RESERVED);
        Attraction other = Attraction.create("회전목마", "테스트 설명", "B구역", 10, 5);
        entityManager.persist(other);
        createReservation(other, NOW.plusHours(1), ReservationStatus.RESERVED);
        flushAndClear();

        // when
        long count = upcomingReservationReader.countUpcomingReservationsByAttractionId(
                attraction.getId(), NOW
        );

        // then
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("미래 예약이 있으면 실제 Reader를 통해 운영 상태 변경을 거절하고 예약 건수를 알린다")
    void updateAttractionStatus_UpcomingReservations_RejectsChange() {
        // given
        createReservation(attraction, NOW.plusHours(1), ReservationStatus.RESERVED);
        createReservation(attraction, NOW.plusHours(2), ReservationStatus.RESERVED);
        Long attractionId = attraction.getId();
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("MAINTENANCE");
        flushAndClear();

        try (MockedStatic<KstTime> clock = mockStatic(KstTime.class)) {
            clock.when(KstTime::now).thenReturn(NOW);

            // when & then
            assertThatThrownBy(() -> attractionService.updateAttractionStatus(attractionId, request))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ATTRACTION_HAS_UPCOMING_RESERVATIONS);
                        assertThat(exception.getMessage()).contains("2건");
                    });
        }

        entityManager.clear();
        Attraction unchanged = entityManager.find(Attraction.class, attractionId);
        assertThat(unchanged.getOperationStatus()).isEqualTo(OperationStatus.OPERATING);
        assertThat(unchanged.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("미래 예약이 있으면 실제 Reader를 통해 삭제를 거절하고 기존 상태를 유지한다")
    void deleteAttraction_UpcomingReservation_RejectsDeletion() {
        // given
        createReservation(attraction, NOW.plusHours(1), ReservationStatus.RESERVED);
        Long attractionId = attraction.getId();
        flushAndClear();

        try (MockedStatic<KstTime> clock = mockStatic(KstTime.class)) {
            clock.when(KstTime::now).thenReturn(NOW);

            // when & then
            assertThatThrownBy(() -> attractionService.deleteAttraction(attractionId))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ATTRACTION_HAS_UPCOMING_RESERVATIONS);
                        assertThat(exception.getMessage()).contains("1건");
                    });
        }

        entityManager.clear();
        Attraction unchanged = entityManager.find(Attraction.class, attractionId);
        assertThat(unchanged.getOperationStatus()).isEqualTo(OperationStatus.OPERATING);
        assertThat(unchanged.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("취소된 미래 예약과 다른 어트랙션의 예약은 운영 상태 변경을 막지 않는다")
    void updateAttractionStatus_NoUpcomingReservations_AllowsChange() {
        // given
        createReservation(attraction, NOW.plusHours(1), ReservationStatus.CANCELLED);
        Attraction other = Attraction.create("회전목마", "테스트 설명", "B구역", 10, 5);
        entityManager.persist(other);
        createReservation(other, NOW.plusHours(1), ReservationStatus.RESERVED);
        Long attractionId = attraction.getId();
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("MAINTENANCE");
        flushAndClear();

        // when
        try (MockedStatic<KstTime> clock = mockStatic(KstTime.class)) {
            clock.when(KstTime::now).thenReturn(NOW);
            attractionService.updateAttractionStatus(attractionId, request);
        }
        flushAndClear();

        // then
        Attraction updated = entityManager.find(Attraction.class, attractionId);
        assertThat(updated.getOperationStatus()).isEqualTo(OperationStatus.MAINTENANCE);
        assertThat(updated.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("예약이 없으면 실제 Reader가 0건을 반환하고 소프트 삭제를 허용한다")
    void deleteAttraction_NoReservations_AllowsSoftDeletion() {
        // given
        Long attractionId = attraction.getId();
        flushAndClear();

        // when
        try (MockedStatic<KstTime> clock = mockStatic(KstTime.class)) {
            clock.when(KstTime::now).thenReturn(NOW);
            attractionService.deleteAttraction(attractionId);
        }
        flushAndClear();

        // then
        Attraction deleted = entityManager.find(Attraction.class, attractionId);
        assertThat(deleted).isNotNull();
        assertThat(deleted.getOperationStatus()).isEqualTo(OperationStatus.DELETED);
        assertThat(deleted.getDeletedAt()).isNotNull();
    }

    private void createReservation(Attraction target, LocalDateTime startsAt, ReservationStatus status) {
        AttractionSlot slot = AttractionSlot.create(target, startsAt, 20);
        entityManager.persist(slot);
        Reservation reservation = new Reservation(user, ticket, slot);
        ReflectionTestUtils.setField(reservation, "status", status);
        entityManager.persist(reservation);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}