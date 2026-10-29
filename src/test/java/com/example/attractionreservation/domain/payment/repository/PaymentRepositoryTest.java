package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.PersistenceUnitUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({TestcontainersConfiguration.class, JpaConfig.class, QuerydslConfig.class})
class PaymentRepositoryTest {
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TestEntityManager em;

    private User user;
    private User otherUser;

    @BeforeEach
    void setUp() {
        user = em.persist(createUser("user@email.com"));
        otherUser = em.persist(createUser("other@email.com"));
    }

    @Test
    @DisplayName("본인의 결제 id로 조회하면 결제가 조회된다.")
    void findByIdAndOrderUserId_ownPayment_ReturnsPayment() {
        //given
        Payment payment = savePayment(user);

        //when
        Optional<Payment> result = paymentRepository.findByIdAndOrderUserId(payment.getId(), user.getId());

        //then
        assertThat(result).hasValueSatisfying(found ->
                assertThat(found.getId()).isEqualTo(payment.getId()));
    }

    @Test
    @DisplayName("다른 사용자의 결제 id로 조회하면 조회되지 않는다.")
    void findByIdAndOrderUserId_otherUsersPayment_ReturnsEmpty() {
        //given
        Payment othersPayment = savePayment(otherUser);

        //when
        Optional<Payment> result = paymentRepository.findByIdAndOrderUserId(othersPayment.getId(), user.getId());

        //then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 결제 id로 조회하면 조회되지 않는다.")
    void findByIdAndOrderUserId_notExistingId_ReturnsEmpty() {
        //given
        Long notExistingId = Long.MAX_VALUE;

        //when
        Optional<Payment> result = paymentRepository.findByIdAndOrderUserId(notExistingId, user.getId());

        //then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("결제 주문의 사용자면 pgId로 결제를 조회한다.")
    void findByPgIdAndUserId_owner_Found() {
        //given
        Payment payment = savePayment(user);

        //when
        Optional<Payment> found = paymentRepository.findByPgIdAndUserId(payment.getPgId(), user.getId());

        //then
        assertThat(found).isPresent();
        assertThat(found.get().getPgId()).isEqualTo(payment.getPgId());
    }

    @Test
    @DisplayName("다른 사용자면 pgId로 결제를 조회하지 않는다.")
    void findByPgIdAndUserId_otherUser_Empty() {
        //given
        Payment payment = savePayment(user);

        //when
        Optional<Payment> found = paymentRepository.findByPgIdAndUserId(payment.getPgId(), otherUser.getId());

        //then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 pgId면 결제를 조회하지 않는다.")
    void findByPgIdAndUserId_unknownPgId_Empty() {
        //when
        Optional<Payment> found = paymentRepository.findByPgIdAndUserId("unknown-pg-id", user.getId());

        //then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("결제 단건 조회 시 주문, 주문 항목, 티켓 상품이 함께 조회된다.")
    void findByIdAndOrderUserId_withOrderItems_FetchesOrderItemsAndTicketProducts() {
        //given
        Payment payment = savePayment(user);
        TicketProduct allDay = em.persist(createTicketProduct("종일이용권"));
        TicketProduct afternoon = em.persist(createTicketProduct("오후이용권"));
        saveOrderItem(payment.getOrder(), allDay, 5000, 1);
        saveOrderItem(payment.getOrder(), afternoon, 2500, 2);
        em.clear();

        //when
        Payment found = paymentRepository.findByIdAndOrderUserId(payment.getId(), user.getId()).orElseThrow();

        //then
        PersistenceUnitUtil persistenceUnitUtil = em.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistenceUnitUtil.isLoaded(found, "order")).isTrue();
        assertThat(persistenceUnitUtil.isLoaded(found.getOrder(), "orderItems")).isTrue();
        assertThat(found.getOrder().getOrderItems())
                .allSatisfy(orderItem -> assertThat(persistenceUnitUtil.isLoaded(orderItem, "ticketProduct")).isTrue())
                .extracting(orderItem -> orderItem.getTicketProduct().getName())
                .containsExactlyInAnyOrder("종일이용권", "오후이용권");
    }

    private User createUser(String email) {
        return User.builder()
                .email(email)
                .password("password")
                .nickname("name")
                .build();
    }

    private Payment savePayment(User owner) {
        Order order = em.persist(Order.create(owner, 10000, LocalDate.of(2026, 10, 21)));
        return em.persist(Payment.create(order));
    }

    private TicketProduct createTicketProduct(String name) {
        return new TicketProduct(name, 5000, "설명", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
    }

    // OrderItem은 생성 메서드가 없어 테이블에 직접 저장
    private void saveOrderItem(Order order, TicketProduct ticketProduct, int price, int quantity) {
        em.flush();
        em.getEntityManager()
                .createNativeQuery("insert into order_items (order_id, ticket_product_id, price, quantity) "
                        + "values (:orderId, :ticketProductId, :price, :quantity)")
                .setParameter("orderId", order.getId())
                .setParameter("ticketProductId", ticketProduct.getId())
                .setParameter("price", price)
                .setParameter("quantity", quantity)
                .executeUpdate();
    }
}
