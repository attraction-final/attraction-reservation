package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PaymentCommandService {
    private static final String CANCELLED_PAYMENT_REASON = "취소된 주문의 결제";

    private final PaymentRepository paymentRepository;
    private final PaymentCompensationService paymentCompensationService;
    private final TicketProductService ticketProductService;
    private final TicketService ticketService;

    /**
     * PG에서 결제 완료가 확인된 결제를 결제 완료 처리하고 주문도 결제 완료로 변경한다.
     *
     * <p>승인 API와 PG 웹훅에서 같은 결제로 여러 번 호출될 수 있으므로 멱등하게 동작한다.
     * <ul>
     *     <li>결제를 비관적 락으로 조회해 동시에 호출돼도 한 번만 반영한다.</li>
     *     <li>이미 결제 완료된 결제는 예외 없이 현재 결제 정보를 반환한다.</li>
     * </ul>
     *
     * <p>사용자가 취소한 결제가 PG에서 결제 완료된 경우(PG 결제 대기 중 취소)에는 같은 트랜잭션에서
     * PG 보상 취소 요청을 저장하고(Outbox) CANCELLED 상태의 결제 정보를 반환한다. 원장 확인과 보상 요청이
     * 함께 커밋되므로 그 사이에 서버가 종료돼도 보상 요청이 사라지지 않는다. 보상 요청이 이미 있으면 다시 만들지 않는다.
     *
     * <p>PG 조회와 금액 검증은 트랜잭션 밖(호출하는 쪽)에서 수행한다.
     *
     * @param paidAt   PG 조회로 확인한 결제 완료 시각
     * @param pgAmount PG가 실제 결제한 금액. 취소된 결제의 보상 금액으로 쓴다
     * @return 결제 정보. 사용자가 취소한 결제면 status가 CANCELLED다
     * @throws BusinessException 결제가 없으면 {@code PAYMENT_NOT_FOUND},
     *                           실패 처리된 결제면 {@code PAYMENT_ALREADY_PROCESSED},
     *                           그 밖에 결제 완료로 전이할 수 없으면 {@code PAYMENT_INVALID_STATUS_TRANSITION}
     */
    @Transactional
    public ConfirmPaymentResponse approvePayment(String pgId, Instant paidAt, Long pgAmount) {
        Payment payment = getPaymentByPgIdForUpdate(pgId);

        // 이미 결제 처리된 경우
        if (payment.getStatus() == PaymentStatus.PAID) {
            return ConfirmPaymentResponse.from(payment);
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        // PG 결제 대기 중 사용자가 취소한 경우. PG에서 결제된 금액을 돌려주도록 보상 요청을 함께 커밋한다.
        if (payment.getStatus() == PaymentStatus.CANCELLED) {
            if (isCancelledWithoutCompensation(payment)) {
                paymentCompensationService.saveCompensatingEvent(pgId, pgAmount, CANCELLED_PAYMENT_REASON);
            }
            return ConfirmPaymentResponse.from(payment);
        }

        payment.markAsPaid(paidAt);
        payment.getOrder().markAsPaid();
        paymentRepository.save(payment);

        // 결제 승인 후 티켓 발급
        ticketService.issueTickets(payment.getOrder());

        return ConfirmPaymentResponse.from(payment);
    }

    /**
     * 결제 대기 상태의 결제를 실패 처리하고 주문을 취소한다. PG에서 결제가 실패한 경우에 사용하며 보상은 없다.
     *
     * <p>결제를 비관적 락으로 조회하므로 동시에 호출돼도 한 번만 실패 처리된다.
     *
     * @return 이번 호출에서 실패 처리했으면 true, 이미 결제 대기 상태가 아니어서 건너뛰었으면 false
     * @throws BusinessException 결제가 없으면 {@code PAYMENT_NOT_FOUND}
     */
    @Transactional
    public boolean failPayment(String pgId) {
        return markAsFailedIfPending(getPaymentByPgIdForUpdate(pgId));
    }

    /**
     * 결제 대기 상태의 결제를 실패 처리하고 주문을 취소하면서, 같은 트랜잭션에서 PG 보상 취소 요청을 저장한다(Outbox).
     * PG에서는 결제됐지만 원장에 반영할 수 없는 경우(금액 불일치)에 사용한다.
     *
     * <p>실패 처리와 보상 요청이 함께 커밋되므로, 커밋 후 PG 취소 전에 서버가 종료돼도 보상 요청이 남는다.
     * 보상 요청 저장에 실패하면 실패 처리도 함께 롤백된다.
     * 사용자가 이미 취소한 결제는 실패 처리 없이 보상 요청만 저장한다.
     *
     * @param pgAmount PG가 실제 결제한 금액(취소할 금액)
     * @return 이번 호출에서 보상을 요청했으면 true, 이미 처리된 결제여서 건너뛰었으면 false
     * @throws BusinessException 결제가 없으면 {@code PAYMENT_NOT_FOUND}
     */
    @Transactional
    public boolean failPaymentWithCompensation(String pgId, Long pgAmount, String reason) {
        Payment payment = getPaymentByPgIdForUpdate(pgId);
        if (!markAsFailedIfPending(payment) && !isCancelledWithoutCompensation(payment)) {
            return false;
        }

        paymentCompensationService.saveCompensatingEvent(pgId, pgAmount, reason);
        return true;
    }

    private boolean markAsFailedIfPending(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return false;
        }

        payment.markAsFailed();
        payment.getOrder().markAsCancelled();

        // 재고 복구
        ticketProductService.updateTicketProductsStockIncrease(payment.getOrder().getQuantityByTicketProductId());

        return true;
    }

    // 사용자가 취소한 결제는 PG 결제가 남아 있을 수 있다. 보상 요청은 결제당 하나(pg_id UNIQUE)라 이미 있으면 만들지 않는다.
    private boolean isCancelledWithoutCompensation(Payment payment) {
        return payment.getStatus() == PaymentStatus.CANCELLED
                && paymentCompensationService.findIdByPgId(payment.getPgId()).isEmpty();
    }

    private Payment getPaymentByPgIdForUpdate(String pgId) {
        return paymentRepository.findWithLockByPgId(pgId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
