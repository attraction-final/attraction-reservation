package com.example.attractionreservation.domain.refund.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.refund.dto.ClaimedRefund;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.RefundItem;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, RefundCommandService.class})
class RefundCommandServiceTest {

    @Autowired
    private RefundCommandService refundCommandService;

    @Autowired
    private EntityManager entityManager;

    private Instant now;
    private Payment payment;
    private Payment otherPayment;
    private OrderItem adult;
    private OrderItem child;

    // 성인 10,000원 × 3장, 어린이 5,000원 × 4장 = 50,000원 결제
    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        entityManager.persist(user);

        TicketProduct ticketProduct = new TicketProduct(
                "종일이용권", 10000, "하루 동안 이용 가능한 입장권", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
        entityManager.persist(ticketProduct);

        Order order = Order.create(user, 50000);
        adult = addOrderItem(order, ticketProduct, 10000, 3);
        child = addOrderItem(order, ticketProduct, 5000, 4);
        order.markAsPaid();
        entityManager.persist(order);

        payment = Payment.create(order);
        payment.markAsPaid(now);
        entityManager.persist(payment);
    }

    @Test
    @DisplayName("환불을 점유하면 PROCESSING이 되고, PG 취소 가능 금액은 결제 금액에서 완료된 환불 금액만 뺀 값이다.")
    void claim_requested_ClaimedWithCancellableAmount() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 1);   // PG에 반영된 10,000원
        saveRefund(RefundStatus.FAILED, child, 1);      // 아직 PG에 반영되지 않은 5,000원
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 2);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(refundId, now);

        //then
        assertThat(claimed).contains(new ClaimedRefund(
                refundId, payment.getPgId(), 20000L, "단순 변심", 40000L, 10000L, 1, null, null));
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(found.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 점유된 환불은 다시 점유하지 않는다.")
    void claim_processing_NotClaimed() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(refundId, now);

        //then
        assertThat(claimed).isEmpty();
        assertThat(find(refundId).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 결제의 다른 환불이 PG 취소 중이면 점유하지 않고 상태도 바꾸지 않는다.")
    void claim_otherRefundProcessing_NotClaimed() {
        //given
        Long inFlight = saveRefund(RefundStatus.REQUESTED, adult, 1);
        Long waiting = saveRefund(RefundStatus.REQUESTED, child, 1);
        refundCommandService.claim(inFlight, now);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(waiting, now);

        //then
        assertThat(claimed).isEmpty();
        Refund found = find(waiting);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(found.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("완료된 환불은 점유하지 않는다.")
    void claim_completed_NotClaimed() {
        //given
        Long refundId = saveRefund(RefundStatus.COMPLETED, adult, 1);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(refundId, now.plus(Duration.ofDays(1)));

        //then
        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("일부 상품의 환불을 완료하면 환불은 COMPLETED, 결제와 주문은 PARTIALLY_REFUNDED가 된다.")
    void recordComplete_partOfItems_PartiallyRefunded() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        refundCommandService.recordComplete(refundId, 1, now);
        flushAndClear();

        //then
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(found.getCompletedAt()).isEqualTo(now);
        Payment foundPayment = findPayment();
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(foundPayment.getOrder().getStatus()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("이번 환불로 모든 상품이 환불되면 결제와 주문은 REFUNDED가 된다.")
    void recordComplete_lastItems_Refunded() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 3);
        saveRefund(RefundStatus.COMPLETED, child, 1);
        Long refundId = saveRefund(RefundStatus.REQUESTED, child, 3);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        refundCommandService.recordComplete(refundId, 1, now);
        flushAndClear();

        //then
        Payment foundPayment = findPayment();
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(foundPayment.getOrder().getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("다른 환불이 아직 재시도 대기 중이면 이번 환불이 완료돼도 PARTIALLY_REFUNDED다.")
    void recordComplete_otherRefundFailed_PartiallyRefunded() {
        //given
        saveRefund(RefundStatus.FAILED, adult, 3);
        Long refundId = saveRefund(RefundStatus.REQUESTED, child, 4);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        refundCommandService.recordComplete(refundId, 1, now);
        flushAndClear();

        //then
        assertThat(findPayment().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("PG 취소 실패를 기록하면 FAILED가 되고, 최대 시도 횟수를 넘으면 DEAD를 반환한다.")
    void recordFailure_untilMaxAttempts_FailedThenDead() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        RefundStatus firstStatus = null;
        RefundStatus lastStatus = null;

        //when
        for (int i = 0; i < 5; i++) {
            Instant attemptAt = now.plus(Duration.ofHours(i));
            refundCommandService.claim(refundId, attemptAt);
            lastStatus = refundCommandService.recordFailure(refundId, i + 1, "결제 취소에 실패했습니다.", attemptAt);
            if (i == 0) {
                firstStatus = lastStatus;
            }
        }

        //then
        assertThat(firstStatus).isEqualTo(RefundStatus.FAILED);
        assertThat(lastStatus).isEqualTo(RefundStatus.DEAD);
        assertThat(find(refundId).getLastError()).isEqualTo("결제 취소에 실패했습니다.");
    }

    @Test
    @DisplayName("재시도하면 안 되는 실패를 기록하면 시도 횟수와 관계없이 바로 DEAD가 된다.")
    void recordDead_processing_DeadWithoutRetry() {
        //given
        Long refundId = saveRefund(RefundStatus.PROCESSING, adult, 1);

        //when
        refundCommandService.recordDead(refundId, 1, "이미 취소된 결제입니다.");

        //then
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.DEAD);
        assertThat(found.getLastError()).isEqualTo("이미 취소된 결제입니다.");
        assertThat(found.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 완료된 환불은 DEAD로 기록하지 않는다.")
    void recordDead_completed_Fail() {
        //given
        Long refundId = saveRefund(RefundStatus.COMPLETED, adult, 1);

        //when & then
        assertThatThrownBy(() -> refundCommandService.recordDead(refundId, 1, "이미 취소된 결제입니다."))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertThat(find(refundId).getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    @DisplayName("처리 대상으로 요청된 환불과 재시도 시각이 지난 실패만 조회한다.")
    void findProcessableIds_mixedStatuses_OnlyProcessable() {
        //given
        Long requested = saveRefund(RefundStatus.REQUESTED, adult, 1);
        Long retryDue = saveRefund(RefundStatus.FAILED, adult, 1, now.minus(Duration.ofMinutes(10)));
        saveRefund(RefundStatus.FAILED, child, 1, now);            // 1분 뒤 재시도
        saveRefund(RefundStatus.COMPLETED, child, 1);
        // 방금 점유됨. 같은 결제에 두면 위 후보들이 같은 결제의 PG 취소 중 환불 때문에 빠지므로 다른 결제에 둔다.
        OrderItem otherItem = createOtherPaymentItem();
        saveRefund(otherPayment, RefundStatus.PROCESSING, otherItem, 1, now);
        flushAndClear();

        //when
        List<Long> ids = refundCommandService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(requested, retryDue);
    }

    @Test
    @DisplayName("처리 중으로 멈춘 시각이 기준 시간과 같으면 처리 대상으로 조회된다.")
    void findProcessableIds_staleProcessingAtBoundary_Included() {
        //given
        Long stale = saveRefund(RefundStatus.PROCESSING, adult, 1);
        flushAndClear();
        entityManager.createQuery("update Refund r set r.updatedAt = :updatedAt where r.id = :id")
                .setParameter("updatedAt", now.minus(RefundCommandService.STALE_PROCESSING_TIMEOUT))
                .setParameter("id", stale)
                .executeUpdate();
        flushAndClear();

        //when
        List<Long> ids = refundCommandService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(stale);
    }

    @Test
    @DisplayName("같은 결제의 다른 환불이 확정 대기 중이면 claim에서 건너뛸 후보이므로 처리 대상으로 조회하지 않는다.")
    void findProcessableIds_otherRefundPgPending_Excluded() {
        //given
        saveRefund(RefundStatus.PG_PENDING, adult, 1);
        saveRefund(RefundStatus.REQUESTED, child, 1);
        saveRefund(RefundStatus.FAILED, child, 1, now.minus(Duration.ofMinutes(10)));
        OrderItem otherItem = createOtherPaymentItem();
        Long otherPaymentRefund = saveRefund(otherPayment, RefundStatus.REQUESTED, otherItem, 1, now);
        flushAndClear();

        //when
        List<Long> ids = refundCommandService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(otherPaymentRefund);
    }

    @Test
    @DisplayName("같은 결제에 처리 중으로 멈춘 환불이 있으면 그 환불만 다시 처리 대상으로 조회하고 대기 중인 환불은 뺀다.")
    void findProcessableIds_otherRefundStaleProcessing_OnlyStale() {
        //given
        Long stale = saveRefund(RefundStatus.PROCESSING, adult, 1);
        saveRefund(RefundStatus.REQUESTED, child, 1);
        flushAndClear();
        entityManager.createQuery("update Refund r set r.updatedAt = :updatedAt where r.id = :id")
                .setParameter("updatedAt", now.minus(RefundCommandService.STALE_PROCESSING_TIMEOUT))
                .setParameter("id", stale)
                .executeUpdate();
        flushAndClear();

        //when
        List<Long> ids = refundCommandService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(stale);
    }

    @Test
    @DisplayName("PG 취소 금액이 완료된 환불 금액과 같으면 이미 맞으므로 아무것도 바꾸지 않는다.")
    void syncWithPgCancelledAmount_inSync_AlreadyDone() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 1);
        Long failed = saveRefund(RefundStatus.FAILED, child, 2);
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 10000L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.ALREADY_DONE);
        assertThat(find(failed).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(findPayment().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"PROCESSING", "FAILED", "DEAD"})
    @DisplayName("PG에 반영된 취소 금액과 같은 미완료 환불이 1건이면 COMPLETED로 바꾸고 결제·주문은 PARTIALLY_REFUNDED가 된다.")
    void syncWithPgCancelledAmount_oneMatched_Synced(RefundStatus status) {
        //given
        Long refundId = saveRefund(status, adult, 1);
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 10000L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.REFUND_SYNCED);
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(found.getCompletedAt()).isEqualTo(now);
        Payment foundPayment = findPayment();
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(foundPayment.getOrder().getStatus()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("PG 기준으로 마지막 환불을 완료하면 결제와 주문은 REFUNDED가 된다.")
    void syncWithPgCancelledAmount_lastRefundMatched_Refunded() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 3);
        Long dead = saveRefund(RefundStatus.DEAD, child, 4);
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 50000L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.REFUND_SYNCED);
        assertThat(find(dead).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        Payment foundPayment = findPayment();
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(foundPayment.getOrder().getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("PG에 보낸 적이 없는 REQUESTED 환불은 금액이 같아도 완료하지 않고 불일치로 본다.")
    void syncWithPgCancelledAmount_onlyRequested_Mismatch() {
        //given
        Long requested = saveRefund(RefundStatus.REQUESTED, adult, 1);
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 10000L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.REFUND_MISMATCH);
        assertThat(find(requested).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(findPayment().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("금액이 같은 미완료 환불이 2건 이상이면 어느 것인지 알 수 없으므로 완료하지 않고 불일치로 본다.")
    void syncWithPgCancelledAmount_multipleMatched_Mismatch() {
        //given
        Long first = saveRefund(RefundStatus.FAILED, adult, 1);    // 10,000원
        Long second = saveRefund(RefundStatus.DEAD, child, 2);     // 10,000원
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 10000L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.REFUND_MISMATCH);
        assertThat(find(first).getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(find(second).getStatus()).isEqualTo(RefundStatus.DEAD);
    }

    @Test
    @DisplayName("일치하는 환불이 없는 취소(외부 취소)는 완료하지 않고 불일치로 본다.")
    void syncWithPgCancelledAmount_noMatched_Mismatch() {
        //given
        Long failed = saveRefund(RefundStatus.FAILED, adult, 1);   // 10,000원
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 7000L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.REFUND_MISMATCH);
        assertThat(find(failed).getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    @DisplayName("PG 취소 금액이 완료된 환불 금액보다 적으면 불일치로 본다.")
    void syncWithPgCancelledAmount_lessThanCompleted_Mismatch() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 1);
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 0L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.REFUND_MISMATCH);
    }

    @Test
    @DisplayName("PG 취소 금액과 같은 환불이 확인 대기(PG_PENDING)면 금액으로 완료하지 않는다(취소 내역 ID로 확정한다).")
    void syncWithPgCancelledAmount_pgPending_NotSynced() {
        //given
        Long pending = saveRefund(RefundStatus.PG_PENDING, adult, 1);   // 10,000원
        flushAndClear();

        //when
        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(payment.getPgId(), 10000L, now);

        //then
        assertThat(result).isEqualTo(PaymentSyncResult.REFUND_MISMATCH);
        assertThat(find(pending).getStatus()).isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("같은 결제의 다른 환불이 PG 취소 확정 대기 중이면 점유하지 않고 상태도 바꾸지 않는다.")
    void claim_otherRefundPgPending_NotClaimed() {
        //given
        saveRefund(RefundStatus.PG_PENDING, adult, 1);
        Long waiting = saveRefund(RefundStatus.REQUESTED, child, 1);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(waiting, now);

        //then
        assertThat(claimed).isEmpty();
        Refund found = find(waiting);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(found.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("멈춘 처리로 보고 다른 처리가 다시 점유했으면 이전 처리의 늦은 완료 결과는 거부한다.")
    void recordComplete_reclaimedByOther_Fail() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        refundCommandService.claim(refundId, now);
        flushAndClear();
        entityManager.createQuery("update Refund r set r.updatedAt = :updatedAt where r.id = :id")
                .setParameter("updatedAt", now.minus(RefundCommandService.STALE_PROCESSING_TIMEOUT))
                .setParameter("id", refundId)
                .executeUpdate();
        flushAndClear();
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when & then
        assertThatThrownBy(() -> refundCommandService.recordComplete(refundId, 1, now))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(found.getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("PG가 취소를 접수하면 확인 대기로 기록하고 취소 내역 ID와 1분 뒤 첫 확인 시각을 저장한다.")
    void recordPgPending_claimed_PgPending() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        refundCommandService.recordPgPending(refundId, 1, "cancel-1", now);

        //then
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.PG_PENDING);
        assertThat(found.getPgCancellationId()).isEqualTo("cancel-1");
        assertThat(found.getPgPendingSince()).isEqualTo(now);
        assertThat(found.getNextRetryAt()).isEqualTo(now.plus(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("확인 시각이 지난 확인 대기 환불만 PG 취소 확인 대상으로 조회하고, 처리 대상에는 넣지 않는다.")
    void findPgCheckableIds_pgPending_OnlyDue() {
        //given
        Long due = saveRefund(RefundStatus.PG_PENDING, adult, 1, now.minus(Duration.ofMinutes(10)));
        saveRefund(RefundStatus.PG_PENDING, child, 1, now);   // 1분 뒤 첫 확인
        flushAndClear();

        //when
        List<Long> checkableIds = refundCommandService.findPgCheckableIds(now, 10);
        List<Long> processableIds = refundCommandService.findProcessableIds(now, 10);

        //then
        assertThat(checkableIds).containsExactly(due);
        assertThat(processableIds).isEmpty();
    }

    @Test
    @DisplayName("PG 확인 대상은 id 순이 아니라 확인 시각이 오래 지난 환불부터 조회한다.")
    void findPgCheckableIds_multipleDue_OldestCheckFirst() {
        //given
        Long recentlyDue = saveRefund(RefundStatus.PG_PENDING, adult, 1, now.minus(Duration.ofMinutes(5)));
        Long longOverdue = saveRefund(RefundStatus.PG_PENDING, child, 1, now.minus(Duration.ofMinutes(20)));
        flushAndClear();

        //when
        List<Long> ids = refundCommandService.findPgCheckableIds(now, 10);
        List<Long> firstOnly = refundCommandService.findPgCheckableIds(now, 1);

        //then
        assertThat(ids).containsExactly(longOverdue, recentlyDue);
        assertThat(firstOnly).containsExactly(longOverdue);
    }

    @Test
    @DisplayName("확인 대상으로 꺼낸 환불에는 완료된 환불 합계, 취소 내역 ID, 확인 대기 시작 시각이 담긴다.")
    void findPgCheckable_due_ClaimedWithCompletedAmount() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 1);   // PG에 반영된 10,000원
        Instant pendingSince = now.minus(Duration.ofMinutes(10));
        Long refundId = saveRefund(RefundStatus.PG_PENDING, child, 2, pendingSince);
        flushAndClear();

        //when
        Optional<ClaimedRefund> pending = refundCommandService.findPgCheckable(refundId, now);

        //then
        assertThat(pending).contains(new ClaimedRefund(refundId, payment.getPgId(), 10000L, "단순 변심",
                40000L, 10000L, 1, "cancel-" + child.getId(), pendingSince));
    }

    @Test
    @DisplayName("결제의 확인 대기 환불을 확인 시각과 관계없이 꺼낸다.")
    void findPgPendingByPgId_pgPending_Found() {
        //given
        Long refundId = saveRefund(RefundStatus.PG_PENDING, adult, 1);   // 1분 뒤 첫 확인
        saveRefund(RefundStatus.FAILED, child, 1);
        flushAndClear();

        //when
        Optional<ClaimedRefund> pending = refundCommandService.findPgPendingByPgId(payment.getPgId());

        //then
        assertThat(pending).map(ClaimedRefund::refundId).contains(refundId);
    }

    @Test
    @DisplayName("조회로 PG 취소 완료를 확인하면 환불은 COMPLETED, 결제와 주문은 PARTIALLY_REFUNDED가 된다.")
    void recordPgCancelled_samePendingCycle_Completed() {
        //given
        Long refundId = saveRefund(RefundStatus.PG_PENDING, adult, 1);
        flushAndClear();

        //when
        refundCommandService.recordPgCancelled(refundId, now, now.plus(Duration.ofMinutes(5)));

        //then
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(found.getCompletedAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
        Payment foundPayment = findPayment();
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(foundPayment.getOrder().getStatus()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("확인한 뒤 새 확인 대기 주기가 됐으면(시작 시각이 다르면) 이전 주기의 확인 결과는 거부한다.")
    void recordPgCancelled_otherPendingCycle_Fail() {
        //given
        Long refundId = saveRefund(RefundStatus.PG_PENDING, adult, 1);
        flushAndClear();

        //when & then
        assertThatThrownBy(() -> refundCommandService.recordPgCancelled(refundId, now.minusSeconds(1), now))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertThat(find(refundId).getStatus()).isEqualTo(RefundStatus.PG_PENDING);
        assertThat(findPayment().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("조회로 PG 취소 실패를 확인하면 FAILED가 되어 취소를 다시 보낼 수 있다.")
    void recordPgCancelFailed_samePendingCycle_Failed() {
        //given
        Long refundId = saveRefund(RefundStatus.PG_PENDING, adult, 1);
        flushAndClear();

        //when
        RefundStatus status = refundCommandService.recordPgCancelFailed(refundId, now, "PG 취소 실패 확인", now);

        //then
        assertThat(status).isEqualTo(RefundStatus.FAILED);
        assertThat(refundCommandService.findProcessableIds(now.plus(Duration.ofMinutes(1)), 10)).containsExactly(refundId);
    }

    private Long saveRefund(RefundStatus status, OrderItem orderItem, int quantity) {
        return saveRefund(status, orderItem, quantity, now);
    }

    private Long saveRefund(RefundStatus status, OrderItem orderItem, int quantity, Instant at) {
        return saveRefund(payment, status, orderItem, quantity, at);
    }

    private Long saveRefund(Payment target, RefundStatus status, OrderItem orderItem, int quantity, Instant at) {
        Refund refund = Refund.create(target, "단순 변심", at);
        refund.addRefundItem(RefundItem.of(orderItem, quantity, orderItem.getQuantity()));
        switch (status) {
            case REQUESTED -> {
            }
            case PROCESSING -> refund.markAsProcessing();
            case FAILED -> {
                refund.markAsProcessing();
                refund.markAsFailed("error", at);
            }
            case COMPLETED -> {
                refund.markAsProcessing();
                refund.markAsComplete(at);
            }
            case DEAD -> {
                refund.markAsProcessing();
                refund.markAsDead("error");
            }
            case PG_PENDING -> {
                refund.markAsProcessing();
                refund.markAsPgPending("cancel-" + orderItem.getId(), at);
            }
            default -> throw new IllegalArgumentException("지원하지 않는 상태: " + status);
        }
        entityManager.persist(refund);
        entityManager.flush();
        return refund.getId();
    }

    // 다른 사용자의 결제(성인 10,000원 × 2장)를 만들고 그 주문 상품을 반환한다. 결제는 otherPayment에 담는다.
    private OrderItem createOtherPaymentItem() {
        User otherUser = User.builder()
                .email("other@email.com")
                .password("password")
                .nickname("other")
                .build();
        entityManager.persist(otherUser);

        Order otherOrder = Order.create(otherUser, 20000);
        OrderItem orderItem = addOrderItem(otherOrder, adult.getTicketProduct(), 10000, 2);
        otherOrder.markAsPaid();
        entityManager.persist(otherOrder);

        otherPayment = Payment.create(otherOrder);
        otherPayment.markAsPaid(now);
        entityManager.persist(otherPayment);
        return orderItem;
    }

    // 주문 생성 기능이 없어 주문 상품을 직접 만든다. 주문을 저장하면 cascade로 함께 저장된다.
    private OrderItem addOrderItem(Order order, TicketProduct ticketProduct, int price, int quantity) {
        OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(orderItem, "order", order);
        ReflectionTestUtils.setField(orderItem, "ticketProduct", ticketProduct);
        ReflectionTestUtils.setField(orderItem, "price", price);
        ReflectionTestUtils.setField(orderItem, "quantity", quantity);
        order.getOrderItems().add(orderItem);
        return orderItem;
    }

    private Refund find(Long refundId) {
        flushAndClear();
        return entityManager.find(Refund.class, refundId);
    }

    private Payment findPayment() {
        flushAndClear();
        return entityManager.find(Payment.class, payment.getId());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
