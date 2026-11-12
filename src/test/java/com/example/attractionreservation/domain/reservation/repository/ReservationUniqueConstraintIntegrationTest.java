package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
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
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// Service와 회원 잠금을 거치지 않고 실제 MySQL의 Generated Column 및 유니크 제약을 검증한다.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, QuerydslConfig.class, ReservationUniqueConstraintIntegrationTest.MySqlConfig.class})
class ReservationUniqueConstraintIntegrationTest {

    private static final String CONSTRAINT_NAME = "uk_reservations_user_slot_active";
    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 10, 30);
    private static final Instant CANCELLED_AT = VISIT_DATE.atTime(14, 0).atZone(KstTime.ZONE).toInstant();

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;
    private TicketProduct product;
    private Ticket ticket;
    private Attraction attraction;
    private AttractionSlot slot;

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
        user = createUser("reservation-unique@test.com");
        product = new TicketProduct(
                "종일 이용권", 50000, "예약 유니크 제약 테스트 상품",
                TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        entityManager.persist(product);
        ticket = createTicket(user);
        attraction = Attraction.create("유니크 제약 테스트 어트랙션", "테스트 설명", "A구역", 30, 5);
        entityManager.persist(attraction);
        slot = AttractionSlot.create(attraction, VISIT_DATE.atTime(16, 0), 20);
        entityManager.persist(slot);
        entityManager.flush();
    }

    @Test
    @DisplayName("서비스 검사를 거치지 않아도 같은 회원과 슬롯의 두 번째 활성 예약은 DB가 거절한다")
    void saveAndFlush_SameUserAndSlotReservedTwice_ThrowsUniqueConstraintViolation() {
        // given
        Reservation first = reservationRepository.saveAndFlush(new Reservation(user, ticket, slot));
        // DB 계산값은 Java 필드에 즉시 반영되지 않으므로 다시 읽어서 확인한다.
        entityManager.refresh(first);
        assertThat(first.getActiveReservation()).isEqualTo(1);
        Reservation duplicate = new Reservation(user, ticket, slot);

        // when & then
        assertThatThrownBy(() -> reservationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CONSTRAINT_NAME);

        // 실패한 INSERT 이후 같은 transaction에서 추가 조회하지 않고 테스트 종료 시 rollback한다.
        System.out.printf("활성 예약 DB 제약 결과: 첫 예약 active=1, 중복 저장 거절, 제약=%s%n", CONSTRAINT_NAME);
    }

    @Test
    @DisplayName("같은 슬롯을 두 번 예약하고 취소한 뒤 다시 예약하면 취소 이력 두 건과 활성 예약 한 건을 허용한다")
    void saveAndFlush_RepeatedCancellationAndRebooking_PreservesHistoryAndOneActiveReservation() {
        // given
        Reservation first = reservationRepository.saveAndFlush(new Reservation(user, ticket, slot));
        entityManager.refresh(first);
        assertThat(first.getActiveReservation()).isEqualTo(1);

        // when
        first.cancelReservation(CANCELLED_AT);
        reservationRepository.flush();
        entityManager.refresh(first);
        Integer firstMarkerAfterCancellation = first.getActiveReservation();

        Reservation second = reservationRepository.saveAndFlush(new Reservation(user, ticket, slot));
        entityManager.refresh(second);
        Integer secondMarkerBeforeCancellation = second.getActiveReservation();
        second.cancelReservation(CANCELLED_AT.plusSeconds(1));
        reservationRepository.flush();
        entityManager.refresh(second);
        Integer secondMarkerAfterCancellation = second.getActiveReservation();

        Reservation third = reservationRepository.saveAndFlush(new Reservation(user, ticket, slot));
        entityManager.refresh(third);

        // then
        List<Reservation> history = reservationRepository.findAll();
        System.out.printf(
                "반복 취소 후 DB 제약 결과: 전체=%d, 상태=%s, 보조값=%s%n",
                history.size(), history.stream().map(Reservation::getStatus).toList(),
                history.stream().map(Reservation::getActiveReservation).toList()
        );
        assertSoftly(softly -> {
            softly.assertThat(firstMarkerAfterCancellation).as("첫 취소 시 보조 값 자동 해제").isNull();
            softly.assertThat(secondMarkerBeforeCancellation).as("두 번째 활성 예약의 DB 계산값").isEqualTo(1);
            softly.assertThat(secondMarkerAfterCancellation).as("두 번째 취소 시 보조 값 자동 해제").isNull();
            softly.assertThat(third.getActiveReservation()).as("최종 활성 예약의 DB 계산값").isEqualTo(1);
            softly.assertThat(history).as("취소 이력 두 건과 새 예약 한 건 보존").hasSize(3);
            softly.assertThat(history).extracting(Reservation::getStatus)
                    .containsExactlyInAnyOrder(ReservationStatus.CANCELLED, ReservationStatus.CANCELLED,
                            ReservationStatus.RESERVED);
            softly.assertThat(history).extracting(Reservation::getActiveReservation)
                    .containsExactlyInAnyOrder(null, null, 1);
            softly.assertThat(history).extracting(Reservation::getId).doesNotHaveDuplicates();
            softly.assertThat(first.getCancelledAt()).isEqualTo(CANCELLED_AT);
            softly.assertThat(second.getCancelledAt()).isEqualTo(CANCELLED_AT.plusSeconds(1));
        });
    }

    @Test
    @DisplayName("서로 다른 회원은 같은 슬롯에 활성 예약을 각각 저장할 수 있다")
    void saveAndFlush_DifferentUsersSameSlot_AllowsBothActiveReservations() {
        // given
        User otherUser = createUser("other-reservation-unique@test.com");
        Ticket otherTicket = createTicket(otherUser);

        // when
        Reservation first = reservationRepository.saveAndFlush(new Reservation(user, ticket, slot));
        Reservation second = reservationRepository.saveAndFlush(new Reservation(otherUser, otherTicket, slot));
        entityManager.refresh(first);
        entityManager.refresh(second);

        // then
        assertThat(reservationRepository.count()).isEqualTo(2);
        assertThat(List.of(first, second)).extracting(Reservation::getActiveReservation).containsExactly(1, 1);
        assertThat(first.getUser().getId()).isNotEqualTo(second.getUser().getId());
    }

    @Test
    @DisplayName("같은 회원은 서로 다른 슬롯에 활성 예약을 각각 저장할 수 있다")
    void saveAndFlush_SameUserDifferentSlots_AllowsBothActiveReservations() {
        // given
        AttractionSlot otherSlot = AttractionSlot.create(attraction, VISIT_DATE.atTime(17, 0), 20);
        entityManager.persist(otherSlot);

        // when
        Reservation first = reservationRepository.saveAndFlush(new Reservation(user, ticket, slot));
        Reservation second = reservationRepository.saveAndFlush(new Reservation(user, ticket, otherSlot));
        entityManager.refresh(first);
        entityManager.refresh(second);

        // then
        assertThat(reservationRepository.count()).isEqualTo(2);
        assertThat(List.of(first, second)).extracting(Reservation::getActiveReservation).containsExactly(1, 1);
        assertThat(first.getAttractionSlot().getId()).isNotEqualTo(second.getAttractionSlot().getId());
    }

    private User createUser(String email) {
        User owner = User.builder().email(email).password("test-password").nickname("제약테스트회원").build();
        entityManager.persist(owner);
        return owner;
    }

    private Ticket createTicket(User owner) {
        Order order = Order.create(owner, 50000, VISIT_DATE);
        entityManager.persist(order);
        OrderItem item = OrderItem.create(order, product, 1);
        entityManager.persist(item);
        Ticket issuedTicket = new Ticket(VISIT_DATE, item);
        issuedTicket.entryTicket(VISIT_DATE.atTime(10, 0).atZone(KstTime.ZONE).toInstant());
        entityManager.persist(issuedTicket);
        return issuedTicket;
    }
}
