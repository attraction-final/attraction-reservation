package com.example.attractionreservation.domain.payment.scheduler;

import com.example.attractionreservation.domain.payment.facade.PaymentCompensationProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 즉시 시도에 실패했거나 시도 전에 서버가 종료된 PG 보상 취소 요청을 주기적으로 재시도한다.
 * 이전 실행이 끝난 뒤 fixed-delay만큼 기다리므로 실행이 겹치지 않는다.
 * 여러 인스턴스나 즉시 시도와 동시에 돌아도 Processor가 요청을 잠그고 상태를 다시 확인해 중복 처리를 막는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.compensation.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentCompensationScheduler {
    private final PaymentCompensationProcessor paymentCompensationProcessor;
    private final int batchSize;

    // pg 사 취소 요청 실패 재처리 스케줄러
    public PaymentCompensationScheduler(
            PaymentCompensationProcessor paymentCompensationProcessor,
            @Value("${payment.compensation.scheduler.batch-size:20}") int batchSize
    ) {
        this.paymentCompensationProcessor = paymentCompensationProcessor;
        this.batchSize = batchSize;
    }

    // 30초마다 실행
    @Scheduled(fixedDelayString = "${payment.compensation.scheduler.fixed-delay:30s}")
    public void processPendingCompensations() {
        try {
            paymentCompensationProcessor.processPending(batchSize);
        } catch (RuntimeException e) {
            // 처리 대상 조회 실패 등. 다음 주기에 다시 시도한다.
            log.error("[Compensation] 스케줄러 실행 실패", e);
        }
    }
}
