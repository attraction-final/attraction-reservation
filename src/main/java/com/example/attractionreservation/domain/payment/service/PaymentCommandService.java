package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {
    private final PaymentRepository paymentRepository;

    /**
     * PG에서 결제 완료가 확인된 결제를 결제 완료 처리하고 주문도 결제 완료로 변경한다.
     *
     * <p>승인 API와 PG 웹훅에서 같은 결제로 여러 번 호출될 수 있으므로 멱등하게 동작한다.
     * <ul>
     *     <li>결제를 비관적 락으로 조회해 동시에 호출돼도 한 번만 반영한다.</li>
     *     <li>이미 결제 완료된 결제는 예외 없이 현재 결제 정보를 반환한다.</li>
     * </ul>
     *
     * <p>PG 조회와 금액 검증은 트랜잭션 밖(호출하는 쪽)에서 수행한다.
     *
     * @param paidAt PG 조회로 확인한 결제 완료 시각
     * @throws BusinessException 결제가 없으면 {@code PAYMENT_NOT_FOUND},
     *                           실패 처리된 결제면 {@code PAYMENT_ALREADY_PROCESSED},
     *                           사용자가 취소한 결제면 {@code PAYMENT_ALREADY_CANCELLED},
     *                           그 밖에 결제 완료로 전이할 수 없으면 {@code PAYMENT_INVALID_STATUS_TRANSITION}
     */
    @Transactional
    public ConfirmPaymentResponse approvePayment(String pgId, Instant paidAt) {
        Payment payment = getPaymentByPgIdForUpdate(pgId);

        // 이미 결제 처리된 경우
        if (payment.getStatus() == PaymentStatus.PAID) {
            return ConfirmPaymentResponse.from(payment);
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        // PG 결제 대기 중 사용자가 취소한 경우. 호출하는 쪽에서 PG 결제를 취소해야 한다.
        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_CANCELLED);
        }

        payment.markAsPaid(paidAt);
        payment.getOrder().markAsPaid();
        paymentRepository.save(payment);

        return ConfirmPaymentResponse.from(payment);
    }

    /**
     * 결제 대기 상태의 결제를 실패 처리하고 주문을 취소한다.
     *
     * <p>결제를 비관적 락으로 조회하므로 동시에 호출돼도 한 번만 실패 처리된다.
     *
     * @return 이번 호출에서 실패 처리했으면 true, 이미 결제 대기 상태가 아니어서 건너뛰었으면 false.
     *         호출하는 쪽은 true일 때만 PG 보상 취소를 수행한다.
     * @throws BusinessException 결제가 없으면 {@code PAYMENT_NOT_FOUND}
     */
    @Transactional
    public boolean failPayment(String pgId) {
        Payment payment = getPaymentByPgIdForUpdate(pgId);

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return false;
        }

        payment.markAsFailed();
        payment.getOrder().markAsCancelled();

        // TODO: 재고 복구 필요
        return true;
    }

    private Payment getPaymentByPgIdForUpdate(String pgId) {
        return paymentRepository.findWithLockByPgId(pgId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
