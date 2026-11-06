package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttractionSlotGenerationServiceTest {

    private static final Long ATTRACTION_ID = 1L;
    // 테스트가 실행되는 날짜와 상관없이 같은 결과가 나오도록 "지금"을 고정해서 넘긴다.
    private static final LocalDate DAY = LocalDate.of(2030, 1, 1);
    private static final LocalDateTime BEFORE_OPEN = DAY.atTime(9, 0);

    @Mock
    private AttractionRepository attractionRepository;

    @Mock
    private AttractionSlotRepository attractionSlotRepository;

    @InjectMocks
    private AttractionSlotGenerationService attractionSlotGenerationService;

    @Captor
    private ArgumentCaptor<List<AttractionSlot>> slotsCaptor;

    // 기본 정책: 10:00 ~ 21:30 시작, 30분 간격, 정원 20명
    private static SlotGenerationPolicy policy(int daysAhead) {
        return new SlotGenerationPolicy(daysAhead, LocalTime.of(10, 0), LocalTime.of(21, 30), 30, 20);
    }

    private void givenAttraction(int durationMinutes) {
        Attraction attraction = Attraction.create("롤러코스터", "빠른 코스터", "A구역", durationMinutes, 5);
        given(attractionRepository.findActiveByIdForUpdate(ATTRACTION_ID)).willReturn(Optional.of(attraction));
    }

    // 운영 시간이 있는 어트랙션 준비 (KAN-117)
    // Attraction 에는 운영 시간을 넣는 메서드가 없어서 테스트에서는 필드에 직접 넣는다.
    private void givenAttractionWithHours(int durationMinutes, LocalTime openTime, LocalTime closeTime) {
        Attraction attraction = Attraction.create("롤러코스터", "빠른 코스터", "A구역", durationMinutes, 5);
        ReflectionTestUtils.setField(attraction, "openTime", openTime);
        ReflectionTestUtils.setField(attraction, "closeTime", closeTime);
        given(attractionRepository.findActiveByIdForUpdate(ATTRACTION_ID)).willReturn(Optional.of(attraction));
    }

    private void givenExistingStartTimes(LocalDateTime... startTimes) {
        given(attractionSlotRepository.findStartTimesByAttractionIdBetween(eq(ATTRACTION_ID), any(), any()))
                .willReturn(List.of(startTimes));
    }

    private List<AttractionSlot> savedSlots() {
        verify(attractionSlotRepository).saveAll(slotsCaptor.capture());
        return slotsCaptor.getValue();
    }

    @Test
    @DisplayName("슬롯이 없는 날을 정책대로 채운다 (10:00~21:30, 30분 간격, 하루 24개)")
    void generateSlots_emptyDays_createsSlotsByPolicy() {
        // given
        givenAttraction(10);
        givenExistingStartTimes();

        // when: 오늘(개장 전) 포함 2일
        int created = attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy(2), BEFORE_OPEN);

        // then
        List<AttractionSlot> slots = savedSlots();
        assertThat(created).isEqualTo(48);
        assertThat(slots).hasSize(48);

        AttractionSlot first = slots.get(0);
        assertThat(first.getStartsAt()).isEqualTo(DAY.atTime(10, 0));
        assertThat(first.getEndsAt()).isEqualTo(DAY.atTime(10, 10)); // 시작 + 탑승 10분 (BR-SLT-01)
        assertThat(first.getCapacity()).isEqualTo(20);
        assertThat(first.getReservedCount()).isZero();
        assertThat(first.getStatus()).isEqualTo(SlotStatus.OPEN);

        assertThat(slots.get(47).getStartsAt()).isEqualTo(DAY.plusDays(1).atTime(21, 30));
    }

    @Test
    @DisplayName("그날 슬롯이 하나라도 있으면(취소 포함) 그날은 건너뛴다")
    void generateSlots_dayWithAnySlot_skipsThatDay() {
        // given: 첫날에 관리자가 만든 슬롯 1개
        givenAttraction(10);
        givenExistingStartTimes(DAY.atTime(13, 0));

        // when
        int created = attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy(2), BEFORE_OPEN);

        // then: 둘째 날만 24개
        assertThat(created).isEqualTo(24);
        assertThat(savedSlots())
                .allSatisfy(slot -> assertThat(slot.getStartsAt().toLocalDate()).isEqualTo(DAY.plusDays(1)));
    }

    @Test
    @DisplayName("오늘은 지금보다 늦게 시작하는 슬롯만 만든다")
    void generateSlots_today_createsOnlyFutureSlots() {
        // given
        givenAttraction(10);
        givenExistingStartTimes();

        // when: 지금 15:10
        int created = attractionSlotGenerationService.generateSlots(
                ATTRACTION_ID, policy(1), DAY.atTime(15, 10));

        // then: 15:30 ~ 21:30 → 13개
        List<AttractionSlot> slots = savedSlots();
        assertThat(created).isEqualTo(13);
        assertThat(slots.get(0).getStartsAt()).isEqualTo(DAY.atTime(15, 30));
    }

    @Test
    @DisplayName("탑승 시간이 간격보다 길면 탑승 시간만큼 띄워 슬롯끼리 겹치지 않는다")
    void generateSlots_longDuration_usesDurationAsStep() {
        // given: 탑승 45분 (간격 30분보다 김)
        givenAttraction(45);
        givenExistingStartTimes();

        // when
        attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy(1), BEFORE_OPEN);

        // then: 10:00, 10:45, ... 21:15(종료 22:00) → 16개, 앞 슬롯 종료 = 다음 슬롯 시작
        List<AttractionSlot> slots = savedSlots();
        assertThat(slots).hasSize(16);
        for (int i = 1; i < slots.size(); i++) {
            assertThat(slots.get(i).getStartsAt()).isEqualTo(slots.get(i - 1).getEndsAt());
        }
        assertThat(slots.get(15).getEndsAt()).isEqualTo(DAY.atTime(22, 0));
    }

    @Test
    @DisplayName("종료 시각이 폐장(22:00)을 넘는 슬롯은 만들지 않는다")
    void generateSlots_endsAfterClose_skipped() {
        // given: 10:15 시작, 30분 간격, 마지막 시작 21:45, 탑승 20분 → 21:45 슬롯은 22:05에 끝남
        givenAttraction(20);
        givenExistingStartTimes();
        SlotGenerationPolicy policy =
                new SlotGenerationPolicy(1, LocalTime.of(10, 15), LocalTime.of(21, 45), 30, 20);

        // when
        attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy, BEFORE_OPEN);

        // then: 10:15 ~ 21:15 → 23개 (21:45 제외)
        List<AttractionSlot> slots = savedSlots();
        assertThat(slots).hasSize(23);
        assertThat(slots.get(22).getStartsAt()).isEqualTo(DAY.atTime(21, 15));
    }

    @Test
    @DisplayName("기간 안의 모든 날에 슬롯이 있으면 저장하지 않는다")
    void generateSlots_allDaysFilled_savesNothing() {
        // given
        givenAttraction(10);
        givenExistingStartTimes(DAY.atTime(10, 0), DAY.plusDays(1).atTime(10, 0));

        // when
        int created = attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy(2), BEFORE_OPEN);

        // then
        assertThat(created).isZero();
        verify(attractionSlotRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("대상 조회 뒤 삭제된 어트랙션은 건너뛴다")
    void generateSlots_deletedAttraction_returnsZero() {
        // given
        given(attractionRepository.findActiveByIdForUpdate(ATTRACTION_ID)).willReturn(Optional.empty());

        // when
        int created = attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy(2), BEFORE_OPEN);

        // then
        assertThat(created).isZero();
        verify(attractionSlotRepository, never()).findStartTimesByAttractionIdBetween(anyLong(), any(), any());
        verify(attractionSlotRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("어트랙션 운영 시작이 정책보다 늦으면 운영 시작부터 만든다")
    void generateSlots_lateOpenTime_startsFromOpenTime() {
        // given: 12:45 ~ 19:30 운영, 탑승 3분 (모니모 러쉬와 같은 조건)
        givenAttractionWithHours(3, LocalTime.of(12, 45), LocalTime.of(19, 30));
        givenExistingStartTimes();

        // when
        int created = attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy(1), BEFORE_OPEN);

        // then: 12:45, 13:15, ... 19:15 → 14개
        List<AttractionSlot> slots = savedSlots();
        assertThat(created).isEqualTo(14);
        assertThat(slots.get(0).getStartsAt()).isEqualTo(DAY.atTime(12, 45));
        assertThat(slots.get(13).getStartsAt()).isEqualTo(DAY.atTime(19, 15));
    }

    @Test
    @DisplayName("탑승이 어트랙션 운영 종료 전에 끝나는 슬롯만 만든다")
    void generateSlots_earlyCloseTime_endsBeforeCloseTime() {
        // given: 운영 시작 정보 없음, 13:00 종료, 탑승 2분 (우주전투기와 같은 조건)
        givenAttractionWithHours(2, null, LocalTime.of(13, 0));
        givenExistingStartTimes();

        // when
        int created = attractionSlotGenerationService.generateSlots(ATTRACTION_ID, policy(1), BEFORE_OPEN);

        // then: 10:00, 10:30, ... 12:30 → 6개, 마지막은 12:32에 끝남
        List<AttractionSlot> slots = savedSlots();
        assertThat(created).isEqualTo(6);
        assertThat(slots).isNotEmpty().allSatisfy(slot ->
                assertThat(slot.getEndsAt()).isBeforeOrEqualTo(DAY.atTime(13, 0)));
    }
}