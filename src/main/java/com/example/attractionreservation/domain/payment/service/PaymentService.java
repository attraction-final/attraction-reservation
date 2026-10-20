package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.dto.PgPaymentResult;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;

    @Transactional
    public void createPayment(Order order) {
        if (order.getTotalAmount() <= 0) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_AMOUNT);
        }

        Payment payment = Payment.create(order);

        paymentRepository.save(payment);
    }

    @Transactional
    public void cancelPayment(Long userId, Long orderId) {
        Payment found = paymentRepository.findWithLockByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (!found.getOrder().getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        if (found.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_PROHIBITED);
        }

        found.markAsCancelled();
        found.getOrder().markAsCancelled();
        paymentRepository.save(found);
    }

    /**
     * PG에서 승인이 확인된 결제를 결제 완료 처리하고 주문도 결제 완료 상태로 변경한다.
     *
     * <p>결제 승인 API와 PG 웹훅 양쪽에서 호출되므로 같은 결제에 대해 여러 번 호출될 수 있으며,
     * 반드시 멱등하게 동작해야 한다.
     * <ul>
     *     <li>이미 결제 완료({@link PaymentStatus#PAID})된 결제는 예외 없이 그대로 반환한다.
     *     먼저 도착한 요청이 처리한 것으로 간주하며, 예외를 던지면 PG가 웹훅을 재전송한다.</li>
     *     <li>승인 API와 웹훅이 동시에 도착해도 한 번만 반영되도록 결제를 비관적 락으로 조회한다.</li>
     *     <li>취소·실패 등 결제 대기가 아닌 상태의 결제는 상태 전이 예외가 발생한다.</li>
     * </ul>
     *
     * <p>{@code result}는 클라이언트 요청이나 웹훅 본문이 아니라 PG 조회·승인 API로 확인한 값이어야 한다.
     * PG 호출은 트랜잭션 밖(호출하는 쪽)에서 수행한다.
     *
     * @param result PG에서 결제 완료로 확인된 결제 결과
     * @throws BusinessException 결제가 없으면 {@link ErrorCode#PAYMENT_NOT_FOUND},
     *                           금액이 다르면 {@link ErrorCode#PAYMENT_INCORRECT_AMOUNT},
     *                           결제 대기 상태가 아니면 {@link ErrorCode#PAYMENT_INVALID_STATUS_TRANSITION}
     */
    @Transactional
    public void approvePayment(PgPaymentResult result) {
        Payment payment = paymentRepository.findWithLockByPgId(result.pgId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() == PaymentStatus.PAID) {
            // 이미 처리된 결제인 경우 스킵
            return;
        }

        if (payment.getAmount() != result.amount()) {
            throw new BusinessException(ErrorCode.PAYMENT_INCORRECT_AMOUNT);
        }

        // FAILED, CANCELLED같은 경우 예외 발생 호출하는 쪽에서 보상 필요
        // **NPE 처리 필요**
        payment.markAsPaid(result.approvedAt());
        payment.getOrder().markAsPaid();
    }
}
