package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private EntityManager entityManager;

    private Long ownerId;
    private Long otherUserId;
    private String pgId;

    @BeforeEach
    void setUp() {
        User owner = createUser("owner@email.com");
        User otherUser = createUser("other@email.com");

        Order order = Order.create(owner, 10000);
        entityManager.persist(order);

        Payment payment = Payment.create(order);
        entityManager.persist(payment);

        ownerId = owner.getId();
        otherUserId = otherUser.getId();
        pgId = payment.getPgId();
    }

    @Test
    @DisplayName("결제 주문의 사용자면 결제를 조회한다.")
    void findByPgIdAndUserId_owner_Found() {
        //when
        Optional<Payment> found = paymentRepository.findByPgIdAndUserId(pgId, ownerId);

        //then
        assertThat(found).isPresent();
        assertThat(found.get().getPgId()).isEqualTo(pgId);
    }

    @Test
    @DisplayName("다른 사용자면 결제를 조회하지 않는다.")
    void findByPgIdAndUserId_otherUser_Empty() {
        //when
        Optional<Payment> found = paymentRepository.findByPgIdAndUserId(pgId, otherUserId);

        //then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 결제면 결제를 조회하지 않는다.")
    void findByPgIdAndUserId_unknownPgId_Empty() {
        //when
        Optional<Payment> found = paymentRepository.findByPgIdAndUserId("unknown-pg-id", ownerId);

        //then
        assertThat(found).isEmpty();
    }

    private User createUser(String email) {
        User user = User.builder()
                .email(email)
                .password("password")
                .nickname("name")
                .build();
        entityManager.persist(user);
        return user;
    }
}
