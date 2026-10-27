package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.dto.PaymentDetailResponse;
import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.PageableDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;

    @Transactional
    public String createPayment(Order order) {
        if (order.getTotalAmount() <= 0) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_AMOUNT);
        }

        Payment payment = Payment.create(order);

        paymentRepository.save(payment);

        return payment.getPgId();
    }

    @Transactional(readOnly = true)
    public PageableDto<PaymentResponse> findAllPaymentsByUserId(Long userId, SearchPaymentCondition condition,
                                                                Pageable pageable) {
        return PageableDto.from(paymentRepository.searchAllByUserId(userId, pageable, condition));
    }

    // 다른 사용자의 결제는 존재 여부를 노출하지 않도록 동일하게 PAYMENT_NOT_FOUND 처리
    @Transactional(readOnly = true)
    public PaymentDetailResponse findPaymentById(Long userId, Long paymentId) {
        return paymentRepository.findByIdAndOrderUserId(paymentId, userId)
                .map(PaymentDetailResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional
    public void cancelledByUser(Long userId, Long orderId) {
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

        // TODO: 재고 복구 로직 필요
        paymentRepository.save(found);
    }

    @Transactional
    public void lockPaymentByOrderIdIfExists(Long orderId) {
        // 결제 승인 경로와 잠금 순서를 맞추기 위해 결제 행을 먼저 잠근다.
        // 여기서는 결제 상태를 변경하지 않는다.
        paymentRepository.findWithLockByOrderId(orderId);
    }

    // 웹훅처럼 로그인 사용자가 없는 경로에서 사용한다. 소유자를 확인하지 않는다.
    @Transactional(readOnly = true)
    public Optional<Payment> findByPgId(String pgId) {
        return paymentRepository.findByPgId(pgId);
    }

    @Transactional(readOnly = true)
    public Payment getOwnedPayment(Long userId, String pgId) {
        return paymentRepository.findByPgIdAndUserId(pgId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional
    public void cancelPendingPaymentIfExists(Long userId, Long orderId) {
        // 결제 정보가 아직 생성되지 않은 주문도 취소할 수 있다.
        Payment payment = paymentRepository.findWithLockByOrderId(orderId)
                .orElse(null);

        if (payment == null) {
            return;
        }

        if (!payment.getOrder().getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        // 대기 상태가 아닌 결제는 취소하지 않는다.
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_PROHIBITED);
        }

        payment.markAsCancelled();
    }
}
