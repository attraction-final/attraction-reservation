package com.example.attractionreservation.domain.attractionSlot.scheduler;

import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotGenerationService;
import com.example.attractionreservation.domain.attractionSlot.service.SlotGenerationPolicy;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 슬롯 자동 생성 스케줄러
 *
 * - 서버가 켜질 때 한 번, 그리고 매일 00:05(KST)에 실행한다.
 * - 매번 "오늘부터 N일" 중 빈 날짜를 채우므로, 서버가 며칠 꺼져 있었어도 다음 실행 때 메워진다.
 * - 서버 1대 기준이다. 여러 대로 늘리면 중복 실행을 막는 장치(ShedLock 등)가 필요하다.
 */
@Component
@Slf4j
@ConditionalOnProperty(
        name = "attraction.slot.generation.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AttractionSlotGenerationScheduler {

    private final AttractionSlotGenerationService attractionSlotGenerationService;
    private final SlotGenerationPolicy policy;

    public AttractionSlotGenerationScheduler(
            AttractionSlotGenerationService attractionSlotGenerationService,
            @Value("${attraction.slot.generation.days-ahead:30}") int daysAhead,
            // LocalTime 으로 바로 받으면 지역(Locale)에 따라 "10:00" 변환이 실패할 수 있어 글자로 받고 직접 바꾼다.
            @Value("${attraction.slot.generation.first-start-time:10:00}") String firstStartTime,
            @Value("${attraction.slot.generation.last-start-time:21:30}") String lastStartTime,
            @Value("${attraction.slot.generation.interval-minutes:30}") int intervalMinutes,
            @Value("${attraction.slot.generation.capacity:20}") int capacity
    ) {
        this.attractionSlotGenerationService = attractionSlotGenerationService;
        // 설정이 잘못되면 여기서 예외가 나서 서버가 뜨지 않는다. (잘못된 슬롯이 대량으로 생기는 것보다 낫다)
        this.policy = new SlotGenerationPolicy(
                daysAhead,
                LocalTime.parse(firstStartTime),
                LocalTime.parse(lastStartTime),
                intervalMinutes,
                capacity
        );
    }

    // 서버가 다 켜진 뒤 한 번 실행: 배포 직후에도 바로 슬롯이 보이게 한다.
    @EventListener(ApplicationReadyEvent.class)
    public void generateOnStartup() {
        generateSlots("STARTUP");
    }

    // 매일 00:05 (한국 시각). 0시 정각은 다른 작업과 겹치기 쉬워 5분 늦춘다.
    @Scheduled(cron = "${attraction.slot.generation.scheduler.cron:0 5 0 * * *}", zone = "Asia/Seoul")
    public void generateDaily() {
        generateSlots("DAILY");
    }

    void generateSlots(String trigger) {
        long startedAt = System.nanoTime();
        LocalDateTime now = KstTime.now();

        List<Long> attractionIds;
        try {
            attractionIds = attractionSlotGenerationService.findTargetAttractionIds();
        } catch (RuntimeException e) {
            log.error("event=ATTRACTION_SLOT_GENERATION_FINISHED trigger={} result=FAILURE errorCode={}",
                    trigger, ErrorCode.INTERNAL_SERVER_ERROR.name(), e);
            return;
        }

        int createdCount = 0;
        int failedCount = 0;
        for (Long attractionId : attractionIds) {
            // 어트랙션 하나가 실패해도 나머지는 계속 만든다.
            try {
                createdCount += attractionSlotGenerationService.generateSlots(attractionId, policy, now);
            } catch (RuntimeException e) {
                failedCount++;
                log.error("event=ATTRACTION_SLOT_GENERATION_FAILED trigger={} attractionId={} errorCode={}",
                        trigger, attractionId, ErrorCode.INTERNAL_SERVER_ERROR.name(), e);
            }
        }

        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("event=ATTRACTION_SLOT_GENERATION_FINISHED trigger={} attractionCount={} createdCount={} failedCount={} durationMs={} result={}",
                trigger, attractionIds.size(), createdCount, failedCount, durationMs,
                failedCount == 0 ? "SUCCESS" : "PARTIAL_FAILURE");
    }
}