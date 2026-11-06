package com.example.attractionreservation.domain.attractionSlot.scheduler;

import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotGenerationService;
import com.example.attractionreservation.domain.attractionSlot.service.SlotGenerationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttractionSlotGenerationSchedulerTest {

    @Mock
    private AttractionSlotGenerationService attractionSlotGenerationService;

    private AttractionSlotGenerationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AttractionSlotGenerationScheduler(
                attractionSlotGenerationService, 30, "10:00", "21:30", 30, 20);
    }

    @Test
    @DisplayName("설정값으로 정책을 만들어 어트랙션마다 생성을 요청한다")
    void generateDaily_eachAttraction_usesConfiguredPolicy() {
        // given
        given(attractionSlotGenerationService.findTargetAttractionIds()).willReturn(List.of(1L, 2L));

        // when
        scheduler.generateDaily();

        // then
        ArgumentCaptor<SlotGenerationPolicy> captor = ArgumentCaptor.forClass(SlotGenerationPolicy.class);
        verify(attractionSlotGenerationService).generateSlots(eq(1L), captor.capture(), any());
        verify(attractionSlotGenerationService).generateSlots(eq(2L), any(), any());
        assertThat(captor.getValue()).isEqualTo(
                new SlotGenerationPolicy(30, LocalTime.of(10, 0), LocalTime.of(21, 30), 30, 20));
    }

    @Test
    @DisplayName("한 어트랙션에서 실패해도 나머지 어트랙션은 계속 만든다")
    void generateDaily_oneAttractionFails_continuesOthers() {
        // given
        given(attractionSlotGenerationService.findTargetAttractionIds()).willReturn(List.of(1L, 2L, 3L));
        given(attractionSlotGenerationService.generateSlots(eq(2L), any(), any()))
                .willThrow(new RuntimeException("DB 오류"));

        // when & then: 예외가 스케줄러 밖으로 나가지 않는다
        assertThatCode(() -> scheduler.generateDaily()).doesNotThrowAnyException();
        verify(attractionSlotGenerationService).generateSlots(eq(1L), any(), any());
        verify(attractionSlotGenerationService).generateSlots(eq(3L), any(), any());
    }

    @Test
    @DisplayName("대상 조회가 실패하면 생성 없이 끝나고 예외를 밖으로 던지지 않는다")
    void generateOnStartup_targetQueryFails_doesNotThrow() {
        // given
        given(attractionSlotGenerationService.findTargetAttractionIds())
                .willThrow(new RuntimeException("DB 연결 실패"));

        // when & then
        assertThatCode(() -> scheduler.generateOnStartup()).doesNotThrowAnyException();
        verify(attractionSlotGenerationService, never()).generateSlots(any(), any(), any());
    }

    @Test
    @DisplayName("잘못된 설정(정원 0)이면 스케줄러를 만들 때 바로 실패한다")
    void constructor_invalidCapacity_throws() {
        assertThatThrownBy(() -> new AttractionSlotGenerationScheduler(
                attractionSlotGenerationService, 30, "10:00", "21:30", 30, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}