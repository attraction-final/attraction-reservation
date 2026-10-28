package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentCompensationRepository;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 실패 처리와 보상 요청이 같은 트랜잭션에서 커밋·롤백되는지 실제 트랜잭션으로 검증한다.
 * 테스트 메서드 트랜잭션에 묶이지 않도록 NOT_SUPPORTED로 실행하고, 데이터는 직접 정리한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, PaymentCommandService.class, PaymentCompensationService.class, TicketProductService.class, TicketService.class, QuerydslConfig.class})
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
    private Long productId;

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

            // 주문으로 재고 10개 중 2개가 차감된 상태(남은 재고 8)다.
            TicketProduct ticketProduct = new TicketProduct(
                    "종일이용권", 5000, "하루 동안 이용 가능한 입장권", TicketType.ALL_DAY, 8, TicketProductStatus.ON_SALE);
            entityManager.persist(ticketProduct);
            productId = ticketProduct.getId();

            Order order = Order.create(user, 10000, LocalDate.now(KstTime.ZONE).plusDays(1));
            order.addOrderItem(ticketProduct, 2);
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
            entityManager.createQuery("delete from Ticket").executeUpdate();
            entityManager.createQuery("delete from PaymentCompensation").executeUpdate();
            entityManager.createQuery("delete from Payment").executeUpdate();
            entityManager.createQuery("delete from OrderItem").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
            entityManager.createQuery("delete from TicketProduct").executeUpdate();
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

    @Test
    @DisplayName("취소된 결제를 승인하면 보상 요청이 같은 트랜잭션에서 커밋되고, 다시 승인해도 추가로 만들지 않는다.")
    void approvePayment_cancelled_CommitCompensationOnce() {
        //given
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createQuery("update Payment p set p.status = :cancelled where p.pgId = :pgId")
                .setParameter("cancelled", PaymentStatus.CANCELLED)
                .setParameter("pgId", pgId)
                .executeUpdate());

        //when
        ConfirmPaymentResponse first = paymentCommandService.approvePayment(pgId, Instant.now(), 10000L);
        ConfirmPaymentResponse second = paymentCommandService.approvePayment(pgId, Instant.now(), 10000L);

        //then
        assertThat(first.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(second.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(paymentCompensationRepository.count()).isEqualTo(1);
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);

        PaymentCompensation compensation = paymentCompensationRepository.findByPgId(pgId).orElseThrow();
        assertThat(compensation.getStatus()).isEqualTo(CompensationStatus.REQUESTED);
        assertThat(compensation.getAmount()).isEqualTo(10000L);
        assertThat(compensation.getReason()).isEqualTo("취소된 주문의 결제");
    }

    @Test
    @DisplayName("금액 불일치로 실패 처리하면 결제 실패·주문 취소·보상 요청과 함께 재고도 복구한다.")
    void failPaymentWithCompensation_pending_RestoresStock() {
        //when
        paymentCommandService.failPaymentWithCompensation(pgId, 9999L, "결제 금액 불일치");

        //then
        assertThat(findStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("보상 요청 저장에 실패하면 이미 복구한 재고도 함께 롤백된다.")
    void failPaymentWithCompensation_compensationFails_StockRollbackTogether() {
        //when & then
        assertThatThrownBy(() -> paymentCommandService.failPaymentWithCompensation(pgId, null, "결제 금액 불일치"))
                .isInstanceOf(BusinessException.class);

        assertThat(findStock()).isEqualTo(8);
    }

    @Test
    @DisplayName("이미 실패 처리된 결제에 다시 요청해도 재고는 한 번만 복구한다.")
    void failPaymentWithCompensation_alreadyFailed_RestoresStockOnce() {
        //given
        paymentCommandService.failPaymentWithCompensation(pgId, 9999L, "결제 금액 불일치");

        //when
        paymentCommandService.failPaymentWithCompensation(pgId, 9999L, "결제 금액 불일치");

        //then
        assertThat(findStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("취소된 결제를 승인해 보상 요청만 저장할 때는 재고를 다시 복구하지 않는다.")
    void approvePayment_cancelled_DoesNotRestoreStock() {
        //given
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createQuery("update Payment p set p.status = :cancelled where p.pgId = :pgId")
                .setParameter("cancelled", PaymentStatus.CANCELLED)
                .setParameter("pgId", pgId)
                .executeUpdate());

        //when
        paymentCommandService.approvePayment(pgId, Instant.now(), 10000L);

        //then
        assertThat(findStock()).isEqualTo(8);
    }

    @Test
    @DisplayName("결제 대기 결제를 승인하면 주문 항목별 수량만큼 방문일 기준 티켓을 함께 커밋한다.")
    void approvePayment_pending_IssueTicketsPerOrderItem() {
        //given: 기존 항목(수량 2)에 수량이 다른 항목(수량 3)을 추가한다.
        transactionTemplate.executeWithoutResult(status -> {
            TicketProduct afternoonProduct = new TicketProduct(
                    "오후이용권", 3000, "오후에 이용 가능한 입장권", TicketType.AFTERNOON, 10, TicketProductStatus.ON_SALE);
            entityManager.persist(afternoonProduct);
            entityManager.find(Order.class, orderId).addOrderItem(afternoonProduct, 3);
        });

        //when
        paymentCommandService.approvePayment(pgId, Instant.now(), 10000L);

        //then
        Map<Long, Long> ticketCounts = transactionTemplate.execute(status -> entityManager
                .createQuery("select t.orderItem.id, count(t) from Ticket t group by t.orderItem.id", Object[].class)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1])));
        Map<Long, Long> orderItemQuantities = transactionTemplate.execute(status -> entityManager
                .createQuery("select oi.id, oi.quantity from OrderItem oi where oi.order.id = :orderId", Object[].class)
                .setParameter("orderId", orderId)
                .getResultList().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> ((Integer) row[1]).longValue())));
        assertThat(orderItemQuantities).hasSize(2).containsValues(2L, 3L);
        assertThat(ticketCounts).isEqualTo(orderItemQuantities);

        LocalDate visitDate = transactionTemplate.execute(status -> entityManager.find(Order.class, orderId).getVisitDate());
        List<LocalDate> validDates = transactionTemplate.execute(status -> entityManager
                .createQuery("select t.validDate from Ticket t", LocalDate.class)
                .getResultList());
        assertThat(validDates).hasSize(5).containsOnly(visitDate);
    }

    private int findStock() {
        return transactionTemplate.execute(status -> entityManager.find(TicketProduct.class, productId).getStock());
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
