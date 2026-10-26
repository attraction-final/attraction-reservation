package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentRequest;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFacade {
    private final PaymentService paymentService;
    private final PaymentCommandService paymentCommandService;
    private final PaymentGateway paymentGateway;

    public ConfirmPaymentResponse confirmPayment(Long userId, String pgId, ConfirmPaymentRequest request) {
        Payment payment = paymentService.getOwnedPayment(userId, pgId);

        if (!request.orderId().equals(payment.getOrder().getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

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
            if (paymentCommandService.failPayment(payment.getPgId())) {
                paymentGateway.cancelPayment(payment.getPgId(), "결제 금액 불일치", pgPayment.totalAmount());
            } else {
                log.error("금액 불일치 결제가 이미 처리됨, PG 보상 취소 생략: pgId={}", payment.getPgId());
            }

            throw new BusinessException(ErrorCode.PAYMENT_INCORRECT_AMOUNT);
        }

        return paymentCommandService.approvePayment(payment.getPgId(), pgPayment.paidAt());
    }

    private boolean amountMatches(Payment payment, PaymentGatewayResponse pgPayment) {
        return payment.getAmount() == pgPayment.totalAmount();
    }
}
