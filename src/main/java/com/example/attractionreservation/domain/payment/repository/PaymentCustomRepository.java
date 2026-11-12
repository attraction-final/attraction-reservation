package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PaymentCustomRepository {
    Page<PaymentResponse> searchAllByUserId(Long userId, Pageable pageable,
                                            SearchPaymentCondition condition);
}
