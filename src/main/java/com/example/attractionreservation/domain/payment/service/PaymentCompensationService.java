package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.repository.PaymentCompensationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PaymentCompensationService {
    private final PaymentCompensationRepository paymentCompensationRepository;

    /**
     * 보상 요청을 저장한다. 결제 실패 처리 트랜잭션 안에서 호출되어 함께 커밋·롤백된다.
     */
    @Transactional
    public void saveCompensatingEvent(String pgId, Long amount, String reason) {
        PaymentCompensation paymentCompensation = PaymentCompensation.request(pgId, amount, reason, Instant.now());

        paymentCompensationRepository.save(paymentCompensation);
    }
}
