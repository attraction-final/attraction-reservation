package com.example.attractionreservation.domain.refund.scheduler;

import com.example.attractionreservation.domain.refund.facade.RefundProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 즉시 시도에 실패했거나 시도 전에 서버가 종료된 환불의 PG 취소를 주기적으로 재시도한다.
 * 이전 실행이 끝난 뒤 fixed-delay만큼 기다리므로 실행이 겹치지 않는다.
 * 여러 인스턴스나 즉시 시도와 동시에 돌아도 Processor가 결제와 환불을 잠그고 상태를 다시 확인해 중복 처리를 막는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.refund.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class RefundScheduler {
    private final RefundProcessor refundProcessor;
    private final int batchSize;
    private final int pgCheckBatchSize;

    public RefundScheduler(
            RefundProcessor refundProcessor,
            @Value("${payment.refund.scheduler.batch-size:20}") int batchSize,
            // PG 장애로 PG_PENDING이 쌓여도 확인(PG 조회) 때문에 PG 취소 처리가 밀리지 않도록 한 주기의 확인 건수를 따로 제한한다.
            @Value("${payment.refund.scheduler.pg-check-batch-size:5}") int pgCheckBatchSize
    ) {
        this.refundProcessor = refundProcessor;
        this.batchSize = batchSize;
        this.pgCheckBatchSize = pgCheckBatchSize;
    }

    @Scheduled(fixedDelayString = "${payment.refund.scheduler.fixed-delay:30s}")
    public void processPendingRefunds() {
        try {
            refundProcessor.processPending(batchSize, pgCheckBatchSize);
        } catch (RuntimeException e) {
            // 처리 대상 조회 실패 등. 다음 주기에 다시 시도한다.
            log.error("[Refund] 스케줄러 실행 실패", e);
        }
    }
}
