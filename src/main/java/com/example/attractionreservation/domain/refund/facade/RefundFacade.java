package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.domain.refund.dto.CreatePartialRefundRequest;
import com.example.attractionreservation.domain.refund.dto.CreateRefundRequest;
import com.example.attractionreservation.domain.refund.dto.RefundResponse;
import com.example.attractionreservation.domain.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 환불 요청 저장(트랜잭션 1개)과 PG 취소 즉시 시도(트랜잭션 밖)를 조율한다.
 * 즉시 시도는 요청 스레드에서 한 번 하고, 실패해도 환불 요청이 남아 스케줄러가 재시도한다.
 * 응답은 즉시 시도 후의 환불 상태를 담는다.
 */
@Component
@RequiredArgsConstructor
public class RefundFacade {
    private final RefundService refundService;
    private final RefundProcessor refundProcessor;

    public RefundResponse createRefund(Long userId, String pgId, CreateRefundRequest request) {
        Long refundId = refundService.createFullRefund(userId, pgId, request.reason(), Instant.now());
        return processAndFind(refundId);
    }

    public RefundResponse createPartialRefund(Long userId, String pgId, CreatePartialRefundRequest request) {
        Long refundId = refundService.createRefund(
                userId, pgId, request.toRefundQuantities(), request.reason(), Instant.now());
        return processAndFind(refundId);
    }

    private RefundResponse processAndFind(Long refundId) {
        refundProcessor.processById(refundId);
        return refundService.findRefundById(refundId);
    }
}
