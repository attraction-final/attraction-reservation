package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
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
}
