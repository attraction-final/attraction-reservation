package com.example.attractionreservation.domain.order.scheduler;

import com.example.attractionreservation.domain.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "order.cancel.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OrderCancelScheduler {

    private final OrderService orderService;

    // 이전 실행이 끝난 뒤 1분 후 다시 실행하여 만료된 결제 대기 주문을 정리한다.
    @Scheduled(
            fixedDelayString = "${order.cancel.scheduler.delay-ms:60000}",
            initialDelayString = "${order.cancel.scheduler.initial-delay-ms:60000}"
    )
    public void cancelExpiredOrders() {
        int cancelledCount = orderService.updateExpiredOrdersByCancel();

        if (cancelledCount > 0) {
            log.info("결제 대기 주문 자동 취소 완료: {}건", cancelledCount);
        }
    }
}