package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentCompensationRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 실패 처리와 보상 요청이 같은 트랜잭션에서 커밋·롤백되는지 실제 트랜잭션으로 검증한다.
 * 테스트 메서드 트랜잭션에 묶이지 않도록 NOT_SUPPORTED로 실행하고, 데이터는 직접 정리한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, PaymentCommandService.class, PaymentCompensationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentCompensationOutboxTest {

    @Autowired
    private PaymentCommandService paymentCommandService;

    @Autowired
    private PaymentCompensationRepository paymentCompensationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private String pgId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            User user = User.builder()
                    .email("test@email.com")
                    .password("password")
                    .nickname("name")
                    .build();
            entityManager.persist(user);

            Order order = Order.create(user, 10000);
            entityManager.persist(order);

            Payment payment = Payment.create(order);
            entityManager.persist(payment);

            pgId = payment.getPgId();
            orderId = order.getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from PaymentCompensation").executeUpdate();
            entityManager.createQuery("delete from Payment").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    @DisplayName("금액 불일치 실패 처리는 결제 실패·주문 취소·보상 요청을 함께 커밋한다.")
    void failPaymentWithCompensation_pending_CommitTogether() {
        //when
        boolean failed = paymentCommandService.failPaymentWithCompensation(pgId, 9999L, "결제 금액 불일치");

        //then
        assertThat(failed).isTrue();
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(findOrderStatus()).isEqualTo(OrderStatus.CANCELLED);

        PaymentCompensation compensation = paymentCompensationRepository.findByPgId(pgId).orElseThrow();
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.REQUESTED);
        assertThat(compensation.getAmount()).isEqualTo(9999L);
        assertThat(compensation.getReason()).isEqualTo("결제 금액 불일치");
    }

    @Test
    @DisplayName("보상 요청 저장에 실패하면 결제 실패 처리와 주문 취소도 함께 롤백된다.")
    void failPaymentWithCompensation_compensationFails_RollbackTogether() {
        //when & then
        assertThatThrownBy(() -> paymentCommandService.failPaymentWithCompensation(pgId, null, "결제 금액 불일치"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_AMOUNT.getMessage());

        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(findOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(paymentCompensationRepository.findByPgId(pgId)).isEmpty();
    }

    @Test
    @DisplayName("이미 실패 처리된 결제에 다시 요청하면 보상 요청을 추가로 만들지 않는다.")
    void failPaymentWithCompensation_alreadyFailed_NoDuplicate() {
        //given
        paymentCommandService.failPaymentWithCompensation(pgId, 9999L, "결제 금액 불일치");

        //when
        boolean failed = paymentCommandService.failPaymentWithCompensation(pgId, 9999L, "결제 금액 불일치");

        //then
        assertThat(failed).isFalse();
        assertThat(paymentCompensationRepository.count()).isEqualTo(1);
    }

    private PaymentStatus findPaymentStatus() {
        return transactionTemplate.execute(status -> entityManager
                .createQuery("select p.status from Payment p where p.pgId = :pgId", PaymentStatus.class)
                .setParameter("pgId", pgId)
                .getSingleResult());
    }

    private OrderStatus findOrderStatus() {
        return transactionTemplate.execute(status -> entityManager.find(Order.class, orderId).getStatus());
    }
}
