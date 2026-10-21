package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentRequest;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.service.PaymentCommandService;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFacade {
    private static final String AMOUNT_MISMATCH_REASON = "결제 금액 불일치";

    private final PaymentService paymentService;
    private final PaymentCommandService paymentCommandService;
    private final PaymentGateway paymentGateway;
    private final PaymentCompensationProcessor paymentCompensationProcessor;

    public ConfirmPaymentResponse confirmPayment(Long userId, String pgId, ConfirmPaymentRequest request) {
        Payment payment = paymentService.getOwnedPayment(userId, pgId);

        if (!request.orderId().equals(payment.getOrder().getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        return reconcile(payment);
    }

    /**
     * 사용자 확인 없이 PG 결제 상태를 원장에 반영한다. 웹훅 처리에서 사용한다.
     *
     * <p>웹훅 본문은 신뢰하지 않으므로 이벤트 종류와 무관하게 PG를 다시 조회해 판단한다.
     * 업무상 예상되는 결과는 {@link PaymentSyncResult}로 반환하고,
     * 재시도가 필요한 오류(PG 조회 실패, 락 충돌 등)만 예외로 던진다.
     * 금액 불일치 보상 취소는 보상 Outbox가 따로 재시도하므로 결과에 영향을 주지 않는다(COMPENSATED).
     */
    public PaymentSyncResult syncPayment(String pgId) {
        Optional<Payment> found = paymentService.findByPgId(pgId);
        if (found.isEmpty()) {
            return PaymentSyncResult.NOT_OUR_PAYMENT;
        }

        Payment payment = found.get();
        if (payment.getStatus() == PaymentStatus.PAID) {
            return PaymentSyncResult.ALREADY_DONE;
        }

        try {
            reconcile(payment);
            return PaymentSyncResult.APPROVED;
        } catch (BusinessException e) {
            return switch (e.getErrorCode()) {
                case PAYMENT_ALREADY_PROCESSED -> PaymentSyncResult.ALREADY_DONE;
                case PAYMENT_NOT_PAID -> PaymentSyncResult.NOT_PAID;
                case PAYMENT_PG_FAILED -> PaymentSyncResult.PG_FAILED;
                case PAYMENT_INCORRECT_AMOUNT -> PaymentSyncResult.COMPENSATED;
                // approvePayment가 PAID·FAILED를 걸러낸 뒤의 전이 실패라 재시도해도 결과가 같다.
                case PAYMENT_INVALID_STATUS_TRANSITION, ORDER_INVALID_STATUS_TRANSITION -> PaymentSyncResult.STATE_CONFLICT;
                default -> throw e;
            };
        }
    }

    // PG 조회 → 상태·금액 검증 → 원장 반영. 승인 API와 웹훅이 함께 사용한다.
    private ConfirmPaymentResponse reconcile(Payment payment) {
        if (payment.getStatus() == PaymentStatus.PAID) {
            return ConfirmPaymentResponse.from(payment);
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        PaymentGatewayResponse pgPayment = paymentGateway.getPayment(payment.getPgId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND));

        if (!pgPayment.isPaid()) {
            if (pgPayment.isFailed()) {
                paymentCommandService.failPayment(payment.getPgId());
                throw new BusinessException(ErrorCode.PAYMENT_PG_FAILED);
            }

            // 결제 대기 등 아직 결제가 완료되지 않은 상태
            throw new BusinessException(ErrorCode.PAYMENT_NOT_PAID);
        }

        if (!amountMatches(payment, pgPayment)) {
            // 결제 실패 처리와 보상 요청을 함께 커밋한 뒤 PG 취소를 즉시 한 번 시도한다.
            // 즉시 시도가 실패해도 보상 요청이 남아 재시도되므로 여기서는 예외를 던지지 않는다.
            if (paymentCommandService.failPaymentWithCompensation(
                    payment.getPgId(), pgPayment.totalAmount(), AMOUNT_MISMATCH_REASON)) {
                paymentCompensationProcessor.processByPgId(payment.getPgId());
            } else {
                // 다른 요청이 먼저 실패 처리와 보상 요청까지 마친 경우
                log.info("금액 불일치 결제가 이미 처리됨, 보상 요청 생략: pgId={}", payment.getPgId());
            }

            throw new BusinessException(ErrorCode.PAYMENT_INCORRECT_AMOUNT);
        }

        return paymentCommandService.approvePayment(payment.getPgId(), pgPayment.paidAt());
    }

    private boolean amountMatches(Payment payment, PaymentGatewayResponse pgPayment) {
        return payment.getAmount() == pgPayment.totalAmount();
    }
}
