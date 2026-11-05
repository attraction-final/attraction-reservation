package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;

import java.time.LocalTime;

/**
 * 슬롯 자동 생성 규칙 (application.yaml 의 attraction.slot.generation 값으로 만든다)
 *
 * @param daysAhead       오늘부터 며칠치를 채울지 (오늘 포함)
 * @param firstStartTime  하루 첫 슬롯 시작 시각
 * @param lastStartTime   하루 마지막 슬롯 시작 시각 (종료가 폐장을 넘으면 만들지 않는다)
 * @param intervalMinutes 슬롯 시작 간격(분). 탑승 시간이 더 길면 탑승 시간만큼 띄운다
 * @param capacity        슬롯 하나의 정원
 */
public record SlotGenerationPolicy(
        int daysAhead,
        LocalTime firstStartTime,
        LocalTime lastStartTime,
        int intervalMinutes,
        int capacity
) {

    // 잘못된 설정으로 서버가 이상한 슬롯을 대량으로 만들지 않도록 시작할 때 바로 막는다.
    public SlotGenerationPolicy {
        if (daysAhead < 1 || daysAhead > 365) {
            throw new IllegalArgumentException("daysAhead 는 1~365 사이여야 합니다: " + daysAhead);
        }
        if (intervalMinutes < 1) {
            throw new IllegalArgumentException("intervalMinutes 는 1 이상이어야 합니다: " + intervalMinutes);
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 는 1 이상이어야 합니다: " + capacity);
        }
        if (firstStartTime == null || lastStartTime == null || firstStartTime.isAfter(lastStartTime)) {
            throw new IllegalArgumentException("firstStartTime 은 lastStartTime 보다 늦을 수 없습니다.");
        }
        if (firstStartTime.isBefore(AttractionSlot.PARK_OPEN_TIME)) {
            throw new IllegalArgumentException("firstStartTime 은 개장 시각(10:00) 이후여야 합니다: " + firstStartTime);
        }
    }
}