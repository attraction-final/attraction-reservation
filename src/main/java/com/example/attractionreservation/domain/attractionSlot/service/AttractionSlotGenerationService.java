package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 슬롯 자동 생성 (스케줄러가 매일 호출)
 *
 * 규칙: "그날 슬롯이 하나도 없는 날"만 정책대로 채운다.
 *  - 그날 슬롯이 하나라도 있으면(취소된 슬롯 포함) 관리자가 관리하는 날로 보고 건드리지 않는다.
 *    → 관리자가 직접 만든 시간표, 지운 슬롯이 다시 생기지 않는다.
 *  - 오늘은 지금보다 늦게 시작하는 슬롯만 만든다.
 *  - 종료 시각이 운영 시간(10:00~22:00)을 넘는 슬롯은 만들지 않는다. (BR-SLT-03)
 *  - 어트랙션에 운영 시간(open_time, close_time)이 있으면 그 안에서만 만든다. (KAN-117)
 *    시작은 open_time 이후, 끝은 close_time 이전. 값이 없으면(NULL) 정책 시간표를 그대로 쓴다.
 *  - 간격을 탑승 시간 이상으로 두어 같은 어트랙션의 슬롯끼리 겹치지 않는다. (BR-SLT-02)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttractionSlotGenerationService {

    private final AttractionRepository attractionRepository;
    private final AttractionSlotRepository attractionSlotRepository;

    // 슬롯을 채울 대상: 삭제되지 않은 모든 어트랙션 (점검 중이어도 미리 만들어 둔다)
    @Transactional(readOnly = true)
    public List<Long> findTargetAttractionIds() {
        return attractionRepository.findAllActiveIds();
    }

    /**
     * 어트랙션 하나의 빈 날짜를 채운다. 어트랙션마다 따로 트랜잭션을 연다.
     * → 한 어트랙션에서 실패해도 다른 어트랙션의 슬롯은 정상 저장된다.
     *
     * @return 새로 만든 슬롯 수
     */
    @Transactional
    public int generateSlots(Long attractionId, SlotGenerationPolicy policy, LocalDateTime now) {
        // 1. 어트랙션을 잠그고 조회한다.
        //    관리자 슬롯 등록(AttractionSlotService)도 같은 잠금을 쓰므로,
        //    "관리자가 그날 첫 슬롯을 만드는 순간"과 겹쳐도 시간이 겹치는 슬롯이 생기지 않는다.
        Optional<Attraction> found = attractionRepository.findActiveByIdForUpdate(attractionId);
        if (found.isEmpty()) {
            return 0; // 대상 조회 뒤에 삭제된 어트랙션
        }
        Attraction attraction = found.get();

        // 2. 채울 기간: 오늘 0시 ~ (오늘 + daysAhead일) 0시
        LocalDate today = now.toLocalDate();
        LocalDateTime rangeStart = today.atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(policy.daysAhead()).atStartOfDay();

        // 3. 이미 슬롯이 있는 날짜 (취소된 슬롯 포함). 쿼리 1번으로 기간 전체를 확인한다.
        Set<LocalDate> filledDays = attractionSlotRepository
                .findStartTimesByAttractionIdBetween(attractionId, rangeStart, rangeEnd)
                .stream()
                .map(LocalDateTime::toLocalDate)
                .collect(Collectors.toSet());

        // 4. 빈 날짜만 시간표대로 만든다.
        List<AttractionSlot> newSlots = new ArrayList<>();
        for (int i = 0; i < policy.daysAhead(); i++) {
            LocalDate day = today.plusDays(i);
            if (!filledDays.contains(day)) {
                newSlots.addAll(createDaySlots(attraction, day, policy, now));
            }
        }

        if (newSlots.isEmpty()) {
            return 0;
        }
        attractionSlotRepository.saveAll(newSlots);

        log.info("event=ATTRACTION_SLOT_GENERATED attractionId={} createdCount={} from={} toExclusive={}",
                attractionId, newSlots.size(), today, rangeEnd.toLocalDate());
        return newSlots.size();
    }

    // 하루치 슬롯 만들기
    private List<AttractionSlot> createDaySlots(Attraction attraction, LocalDate day,
                                                SlotGenerationPolicy policy, LocalDateTime now) {
        int durationMinutes = attraction.getDurationMinutes();
        // 탑승 시간이 간격보다 길면 다음 슬롯과 겹치므로 탑승 시간만큼 띄운다. (BR-SLT-02)
        int stepMinutes = Math.max(policy.intervalMinutes(), durationMinutes);

        // LocalTime 으로 더하면 자정을 넘을 때 0시로 돌아가 무한 반복될 수 있어서, "하루 중 몇 분째"인 숫자로 센다.
        int firstMinute = firstStartMinute(attraction, policy);
        int lastMinute = lastStartMinute(attraction, policy, durationMinutes);

        List<AttractionSlot> slots = new ArrayList<>();
        for (int minute = firstMinute; minute <= lastMinute; minute += stepMinutes) {
            LocalDateTime startsAt = day.atStartOfDay().plusMinutes(minute);
            LocalDateTime endsAt = AttractionSlot.calculateEndsAt(startsAt, durationMinutes);

            // 아직 시작 전이고(관리자 등록 규칙과 같다), 폐장 전에 끝나는(BR-SLT-03) 슬롯만 만든다.
            boolean notStartedYet = startsAt.isAfter(now);
            boolean withinOperatingHours = AttractionSlot.isWithinOperatingHours(startsAt, endsAt);
            if (notStartedYet && withinOperatingHours) {
                slots.add(AttractionSlot.create(attraction, startsAt, policy.capacity()));
            }
        }
        return slots;
    }

    // 첫 슬롯 시작 시각(하루 중 몇 분째)
    // 정책 첫 시작(10:00)과 어트랙션 운영 시작 중 "늦은 쪽"을 쓴다.
    // 예) 운영 시작 12:45 → 12:45 부터 / 운영 시작 정보 없음 → 10:00 부터
    private int firstStartMinute(Attraction attraction, SlotGenerationPolicy policy) {
        int policyFirst = toMinuteOfDay(policy.firstStartTime());
        LocalTime openTime = attraction.getOpenTime();
        if (openTime == null) {
            return policyFirst;
        }
        return Math.max(policyFirst, toMinuteOfDay(openTime));
    }

    // 마지막 슬롯 시작 시각(하루 중 몇 분째)
    // 정책 마지막 시작(21:30)과 "운영 종료 - 탑승 시간" 중 "이른 쪽"을 쓴다.
    // 탑승이 운영 종료 전에 끝나야 하므로 탑승 시간만큼 앞당긴다.
    // 예) 운영 종료 13:00, 탑승 2분 → 12:58 까지 시작 가능 / 운영 종료 정보 없음 → 21:30 까지
    private int lastStartMinute(Attraction attraction, SlotGenerationPolicy policy, int durationMinutes) {
        int policyLast = toMinuteOfDay(policy.lastStartTime());
        LocalTime closeTime = attraction.getCloseTime();
        if (closeTime == null) {
            return policyLast;
        }
        return Math.min(policyLast, toMinuteOfDay(closeTime) - durationMinutes);
    }

    private static int toMinuteOfDay(LocalTime time) {
        return time.toSecondOfDay() / 60;
    }
}