package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.dto.*;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.UnavailableReason;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttractionSlotServiceTest {

    @Mock
    private AttractionSlotRepository attractionSlotRepository;

    @Mock
    private AttractionRepository attractionRepository;

    @InjectMocks
    private AttractionSlotService attractionSlotService;

    // 테스트가 언제 돌아도 "미래"가 되도록 내일 날짜 사용
    private static final LocalDate TOMORROW = KstTime.now().toLocalDate().plusDays(1);

    // 시간 수정, 정원 수정에 사용
    private void givenSlot(AttractionSlot slot) {
        given(attractionSlotRepository.findById(slot.getId())).willReturn(Optional.of(slot));
    }

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

    // 날짜별 조회
    @Nested
    @DisplayName("날짜별 조회")
    class Search {

        @Test
        @DisplayName("사용자: 날짜 없으면 오늘, 취소 슬롯 제외로 조회")
        void userDefaultToday() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L))
                    .willReturn(Optional.of(createAttraction(OperationStatus.OPERATING)));
            LocalDate today = KstTime.now().toLocalDate();
            given(attractionSlotRepository.findSlotsByAttractionIdAndDate(
                    1L, today.atStartOfDay(), today.plusDays(1).atStartOfDay(), false)).willReturn(List.of());

            SearchAttractionSlotResponse response = attractionSlotService.findAttractionSlotsByDate(1L, null);

            assertThat(response.date()).isEqualTo(today);
            assertThat(response.slots()).isEmpty();
        }

        @Test
        @DisplayName("사용자: 어트랙션이 운영중이 아니면 모든 슬롯 예약 불가")
        void userAttractionNotOperating() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L))
                    .willReturn(Optional.of(createAttraction(OperationStatus.MAINTENANCE)));
            given(attractionSlotRepository.findSlotsByAttractionIdAndDate(eq(1L), any(), any(), eq(false)))
                    .willReturn(List.of(createTomorrowSlot(0, SlotStatus.OPEN)));

            SearchAttractionSlotResponse response = attractionSlotService.findAttractionSlotsByDate(1L, TOMORROW);

            SearchAttractionSlotResponse.SlotItem item = response.slots().get(0);
            assertThat(item.reservable()).isFalse();
            assertThat(item.unavailableReason()).isEqualTo(UnavailableReason.ATTRACTION_NOT_OPERATING);
        }

        @Test
        @DisplayName("사용자: 내일 OPEN 슬롯은 예약 가능")
        void userReservable() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L))
                    .willReturn(Optional.of(createAttraction(OperationStatus.OPERATING)));
            given(attractionSlotRepository.findSlotsByAttractionIdAndDate(eq(1L), any(), any(), eq(false)))
                    .willReturn(List.of(createTomorrowSlot(12, SlotStatus.OPEN)));

            SearchAttractionSlotResponse.SlotItem item =
                    attractionSlotService.findAttractionSlotsByDate(1L, TOMORROW).slots().get(0);

            assertThat(item.reservable()).isTrue();
            assertThat(item.unavailableReason()).isNull();
            assertThat(item.remainingCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("관리자: includeCancelled 값을 그대로 전달")
        void adminIncludeCancelled() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L))
                    .willReturn(Optional.of(createAttraction(OperationStatus.OPERATING)));
            given(attractionSlotRepository.findSlotsByAttractionIdAndDate(eq(1L), any(), any(), eq(true)))
                    .willReturn(List.of(createTomorrowSlot(0, SlotStatus.CANCELLED)));

            SearchAdminAttractionSlotResponse response =
                    attractionSlotService.findAdminAttractionSlotsByDate(1L, TOMORROW, true);

            assertThat(response.slots()).hasSize(1);
            assertThat(response.slots().get(0).status()).isEqualTo(SlotStatus.CANCELLED);
        }

        @Test
        @DisplayName("실패: 어트랙션 없음")
        void attractionNotFound() {
            given(attractionRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

            assertErrorCode(() -> attractionSlotService.findAdminAttractionSlotsByDate(99L, TOMORROW, false),
                    ErrorCode.ATTRACTION_NOT_FOUND);
        }
    }

    // 추가: 도메인 메서드 직접 검증
    @Nested
    @DisplayName("슬롯 예약 불가 사유 판단")
    class FindUnavailableReason {

        private final LocalDateTime startsAt = TOMORROW.atTime(14, 0);
        private final LocalDateTime deadline = startsAt.minusMinutes(30); // 13:30

        @Test
        @DisplayName("마감 1초 전: 예약 가능(null)")
        void oneSecondBeforeDeadline() {
            AttractionSlot slot = createSlot(1L, startsAt, 20, 0, SlotStatus.OPEN);
            assertThat(slot.findUnavailableReason(true, deadline.minusSeconds(1))).isNull();
        }

        @Test
        @DisplayName("마감 정각: 예약 가능(null) — 9장 #2 정각 허용")
        void atDeadline() {
            AttractionSlot slot = createSlot(1L, startsAt, 20, 0, SlotStatus.OPEN);
            assertThat(slot.findUnavailableReason(true, deadline)).isNull();
        }

        @Test
        @DisplayName("마감 1초 후: DEADLINE_PASSED")
        void oneSecondAfterDeadline() {
            AttractionSlot slot = createSlot(1L, startsAt, 20, 0, SlotStatus.OPEN);
            assertThat(slot.findUnavailableReason(true, deadline.plusSeconds(1)))
                    .isEqualTo(UnavailableReason.DEADLINE_PASSED);
        }

        @Test
        @DisplayName("잔여 1석: 예약 가능 / 잔여 0석: FULL")
        void capacityBoundary() {
            AttractionSlot oneLeft = createSlot(1L, startsAt, 20, 19, SlotStatus.OPEN);
            AttractionSlot noneLeft = createSlot(2L, startsAt, 20, 20, SlotStatus.OPEN);

            assertThat(oneLeft.findUnavailableReason(true, deadline)).isNull();
            assertThat(noneLeft.findUnavailableReason(true, deadline)).isEqualTo(UnavailableReason.FULL);
        }

        @Test
        @DisplayName("우선순위: SUSPENDED + 마감 지남이면 SUSPENDED가 먼저")
        void suspendedBeatsDeadline() {
            AttractionSlot slot = createSlot(1L, startsAt, 20, 0, SlotStatus.SUSPENDED);
            assertThat(slot.findUnavailableReason(true, deadline.plusHours(1)))
                    .isEqualTo(UnavailableReason.SUSPENDED);
        }
    }

    // 슬롯 시간 수정
    @Nested
    @DisplayName("슬롯 시간 수정")
    class UpdateTime {

        @Test
        @DisplayName("성공: 시작·종료 시각 변경")
        void success() {
            AttractionSlot slot = createTomorrowSlot(0, SlotStatus.OPEN);
            givenSlot(slot);
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(slot.getAttraction()));
            given(attractionSlotRepository.countOverlappingSlotsExcludingSlotId(eq(1L), eq(31L), any(), any()))
                    .willReturn(0L);

            UpdateAttractionSlotTimeResponse response = attractionSlotService.updateAttractionSlotTime(
                    31L, new UpdateAttractionSlotTimeRequest(TOMORROW.atTime(14, 30)));

            assertThat(response.startsAt()).isEqualTo(TOMORROW.atTime(14, 30));
            assertThat(response.endsAt()).isEqualTo(TOMORROW.atTime(14, 40));
        }

        @Test
        @DisplayName("실패: 슬롯 없음")
        void notFound() {
            given(attractionSlotRepository.findById(99L)).willReturn(Optional.empty());

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotTime(
                    99L, new UpdateAttractionSlotTimeRequest(TOMORROW.atTime(14, 30))), ErrorCode.SLOT_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 예약이 있는 슬롯")
        void hasReservations() {
            givenSlot(createTomorrowSlot(1, SlotStatus.OPEN));

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotTime(
                            31L, new UpdateAttractionSlotTimeRequest(TOMORROW.atTime(14, 30))),
                    ErrorCode.SLOT_HAS_RESERVATIONS);
        }

        @Test
        @DisplayName("실패: 이미 종료된 슬롯")
        void ended() {
            LocalDateTime yesterday = KstTime.now().toLocalDate().minusDays(1).atTime(14, 0);
            givenSlot(createSlot(31L, yesterday, 20, 0, SlotStatus.OPEN));

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotTime(
                            31L, new UpdateAttractionSlotTimeRequest(TOMORROW.atTime(14, 30))),
                    ErrorCode.SLOT_ALREADY_ENDED);
        }

        @Test
        @DisplayName("실패: 다른 슬롯과 겹침")
        void overlap() {
            AttractionSlot slot = createTomorrowSlot(0, SlotStatus.OPEN);
            givenSlot(slot);
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(slot.getAttraction()));
            given(attractionSlotRepository.countOverlappingSlotsExcludingSlotId(eq(1L), eq(31L), any(), any()))
                    .willReturn(1L);

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotTime(
                    31L, new UpdateAttractionSlotTimeRequest(TOMORROW.atTime(14, 30))), ErrorCode.SLOT_TIME_OVERLAP);
            assertThat(slot.getStartsAt()).isEqualTo(TOMORROW.atTime(14, 0)); // 바뀌지 않음
        }
    }

    // 정원 수정
    @Nested
    @DisplayName("슬롯 정원 수정")
    class UpdateCapacity {

        @Test
        @DisplayName("성공: 정원 증가")
        void success() {
            givenSlot(createTomorrowSlot(12, SlotStatus.OPEN));

            UpdateAttractionSlotCapacityResponse response =
                    attractionSlotService.updateAttractionSlotCapacity(31L, new UpdateAttractionSlotCapacityRequest(25));

            assertThat(response.capacity()).isEqualTo(25);
            assertThat(response.remainingCount()).isEqualTo(13);
            assertThat(response.status()).isEqualTo(SlotStatus.OPEN);
        }

        @Test
        @DisplayName("실패: 예약 인원보다 작게")
        void belowReserved() {
            givenSlot(createTomorrowSlot(12, SlotStatus.OPEN));

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotCapacity(
                    31L, new UpdateAttractionSlotCapacityRequest(11)), ErrorCode.SLOT_CAPACITY_BELOW_RESERVED);
        }

        @Test
        @DisplayName("실패: 취소된 슬롯")
        void cancelled() {
            givenSlot(createTomorrowSlot(0, SlotStatus.CANCELLED));

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotCapacity(
                    31L, new UpdateAttractionSlotCapacityRequest(30)), ErrorCode.SLOT_ALREADY_CANCELLED);
        }
    }
}