package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

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

    private User createUser(String email) {
        return User.builder()
                .email(email)
                .password("password")
                .nickname("name")
                .build();
    }

    private Payment savePayment(User owner) {
        Order order = em.persist(Order.create(owner, 10000));
        return em.persist(Payment.create(order));
    }
}
