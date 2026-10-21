package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotResponse;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class AttractionSlotServiceTest {

    @Mock
    private AttractionSlotRepository attractionSlotRepository;

    @Mock
    private AttractionRepository attractionRepository;

    @InjectMocks
    private AttractionSlotService attractionSlotService;

    // 테스트가 언제 돌아도 "미래"가 되도록 내일 날짜 사용
    private static final LocalDate TOMORROW = KstTime.now().toLocalDate().plusDays(1);

    // ===== helper =====
    private Attraction createAttraction(OperationStatus status) {
        Attraction attraction = Attraction.create("롤러코스터", "빠른 코스터", "A구역", 10, 8);
        ReflectionTestUtils.setField(attraction, "id", 1L);
        ReflectionTestUtils.setField(attraction, "operationStatus", status);
        return attraction;
    }

    private AttractionSlot createSlot(Long slotId, LocalDateTime startsAt, int capacity, int reservedCount,
                                      SlotStatus status) {
        AttractionSlot slot = AttractionSlot.create(createAttraction(OperationStatus.OPERATING), startsAt, capacity);
        ReflectionTestUtils.setField(slot, "id", slotId);
        ReflectionTestUtils.setField(slot, "reservedCount", reservedCount);
        ReflectionTestUtils.setField(slot, "status", status);
        return slot;
    }

    private AttractionSlot createTomorrowSlot(int reservedCount, SlotStatus status) {
        return createSlot(31L, TOMORROW.atTime(14, 0), 20, reservedCount, status);
    }

    private void givenSlot(AttractionSlot slot) {
        given(attractionSlotRepository.findById(slot.getId())).willReturn(Optional.of(slot));
    }

    private void assertErrorCode(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    // 등록
    @Nested
    @DisplayName("슬롯 등록")
    class Create {

        @Test
        @DisplayName("성공: 종료 시각 계산, OPEN, 잔여=정원")
        void success() {
            Attraction attraction = createAttraction(OperationStatus.OPERATING);
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
            given(attractionSlotRepository.countOverlappingSlots(eq(1L), any(), any())).willReturn(0L);
            given(attractionSlotRepository.save(any(AttractionSlot.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            CreateAttractionSlotResponse response = attractionSlotService.createAttractionSlot(
                    1L, new CreateAttractionSlotRequest(TOMORROW.atTime(14, 0), 20));

            assertThat(response.endsAt()).isEqualTo(TOMORROW.atTime(14, 10));
            assertThat(response.status()).isEqualTo(SlotStatus.OPEN);
            assertThat(response.reservedCount()).isZero();
            assertThat(response.remainingCount()).isEqualTo(20);
        }

        @Test
        @DisplayName("실패: 어트랙션 없음")
        void attractionNotFound() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

            assertErrorCode(() -> attractionSlotService.createAttractionSlot(
                    99L, new CreateAttractionSlotRequest(TOMORROW.atTime(14, 0), 20)), ErrorCode.ATTRACTION_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 지난 시각")
        void past() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L))
                    .willReturn(Optional.of(createAttraction(OperationStatus.OPERATING)));

            LocalDateTime yesterday = KstTime.now().toLocalDate().minusDays(1).atTime(14, 0);
            assertErrorCode(() -> attractionSlotService.createAttractionSlot(
                    1L, new CreateAttractionSlotRequest(yesterday, 20)), ErrorCode.SLOT_START_IN_PAST);
        }

        @Test
        @DisplayName("실패: 종료가 22:00을 넘음")
        void outOfOperatingHours() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L))
                    .willReturn(Optional.of(createAttraction(OperationStatus.OPERATING)));

            assertErrorCode(() -> attractionSlotService.createAttractionSlot(
                            1L, new CreateAttractionSlotRequest(TOMORROW.atTime(21, 55), 20)),
                    ErrorCode.SLOT_OUT_OF_OPERATING_HOURS);
        }

        @Test
        @DisplayName("실패: 다른 슬롯과 시간 겹침, 저장하지 않음")
        void overlap() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L))
                    .willReturn(Optional.of(createAttraction(OperationStatus.OPERATING)));
            given(attractionSlotRepository.countOverlappingSlots(eq(1L), any(), any())).willReturn(1L);

            assertErrorCode(() -> attractionSlotService.createAttractionSlot(
                    1L, new CreateAttractionSlotRequest(TOMORROW.atTime(14, 0), 20)), ErrorCode.SLOT_TIME_OVERLAP);
            verify(attractionSlotRepository, never()).save(any());
        }
    }
}
