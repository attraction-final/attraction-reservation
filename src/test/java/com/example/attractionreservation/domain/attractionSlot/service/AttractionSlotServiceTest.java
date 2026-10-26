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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttractionSlotServiceTest {

    @Mock
    private AttractionSlotRepository attractionSlotRepository;

    @Mock
    private AttractionRepository attractionRepository;

    @Mock
    private EntityManager entityManager;

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

    @Nested
    @DisplayName("예약용 슬롯 조회")
    class FindForReservation {

        @Test
        @DisplayName("슬롯이 존재하면 해당 슬롯을 반환한다")
        void findAttractionSlotById_ExistingSlot_ReturnsSlot() {
            // given
            AttractionSlot slot = createTomorrowSlot(0, SlotStatus.OPEN);
            givenSlot(slot);

            // when
            AttractionSlot result = attractionSlotService.findAttractionSlotById(31L);

            // then
            assertThat(result).isSameAs(slot);
            verify(attractionSlotRepository).findById(31L);
        }

        @Test
        @DisplayName("존재하지 않는 슬롯을 조회하면 예외가 발생한다")
        void findAttractionSlotById_MissingSlot_ThrowsException() {
            // given
            given(attractionSlotRepository.findById(31L)).willReturn(Optional.empty());

            // when & then
            assertErrorCode(() -> attractionSlotService.findAttractionSlotById(31L),
                    ErrorCode.SLOT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("예약 정원 확보 및 복구")
    class ReservationCapacity {

        @Test
        @DisplayName("마지막 자리를 확보하면 갱신된 인원을 반영하고 자동 마감한다")
        void tryReservationCapacity_LastPlace_AutoClosesSlot() {
            // given
            AttractionSlot slot = createTomorrowSlot(19, SlotStatus.OPEN);
            given(attractionSlotRepository.tryReserveCapacity(31L, SlotStatus.OPEN)).willReturn(1);
            givenSlot(slot);
            doAnswer(invocation -> {
                ReflectionTestUtils.setField(slot, "reservedCount", 20);
                return null;
            }).when(entityManager).refresh(slot);

            // when
            attractionSlotService.tryReservationCapacity(31L);

            // then
            assertThat(slot.getReservedCount()).isEqualTo(20);
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.CLOSED);
            assertThat(slot.isAutoClosed()).isTrue();
            InOrder calls = inOrder(attractionSlotRepository, entityManager);
            calls.verify(attractionSlotRepository).tryReserveCapacity(31L, SlotStatus.OPEN);
            calls.verify(attractionSlotRepository).findById(31L);
            calls.verify(entityManager).refresh(slot);
        }

        @Test
        @DisplayName("정원 확보 후에도 빈자리가 있으면 OPEN 상태를 유지한다")
        void tryReservationCapacity_RemainingPlaces_KeepsOpen() {
            // given
            AttractionSlot slot = createTomorrowSlot(18, SlotStatus.OPEN);
            given(attractionSlotRepository.tryReserveCapacity(31L, SlotStatus.OPEN)).willReturn(1);
            givenSlot(slot);
            doAnswer(invocation -> {
                ReflectionTestUtils.setField(slot, "reservedCount", 19);
                return null;
            }).when(entityManager).refresh(slot);

            // when
            attractionSlotService.tryReservationCapacity(31L);

            // then
            assertThat(slot.getReservedCount()).isEqualTo(19);
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
            assertThat(slot.isAutoClosed()).isFalse();
            verify(entityManager).refresh(slot);
        }

        @Test
        @DisplayName("정원 확보 UPDATE가 실패하면 슬롯 조회와 갱신 없이 예외가 발생한다")
        void tryReservationCapacity_UpdateFailed_ThrowsException() {
            // given
            given(attractionSlotRepository.tryReserveCapacity(31L, SlotStatus.OPEN)).willReturn(0);

            // when & then
            assertErrorCode(() -> attractionSlotService.tryReservationCapacity(31L),
                    ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED);
            verify(attractionSlotRepository, never()).findById(any());
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("자동 마감 슬롯의 정원을 복구하면 갱신된 인원을 반영하고 다시 연다")
        void releaseReservationCapacity_AutoClosedSlot_ReopensSlot() {
            // given
            AttractionSlot slot = createTomorrowSlot(20, SlotStatus.CLOSED);
            ReflectionTestUtils.setField(slot, "autoClosed", true);
            given(attractionSlotRepository.tryReleaseCapacity(31L)).willReturn(1);
            givenSlot(slot);
            doAnswer(invocation -> {
                ReflectionTestUtils.setField(slot, "reservedCount", 19);
                return null;
            }).when(entityManager).refresh(slot);

            // when
            attractionSlotService.releaseReservationCapacity(31L);

            // then
            assertThat(slot.getReservedCount()).isEqualTo(19);
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
            assertThat(slot.isAutoClosed()).isFalse();
            InOrder calls = inOrder(attractionSlotRepository, entityManager);
            calls.verify(attractionSlotRepository).tryReleaseCapacity(31L);
            calls.verify(attractionSlotRepository).findById(31L);
            calls.verify(entityManager).refresh(slot);
        }

        @ParameterizedTest
        @EnumSource(SlotStatus.class)
        @DisplayName("자동 마감이 아닌 슬롯은 정원을 복구해도 기존 운영 상태를 유지한다")
        void releaseReservationCapacity_NotAutoClosed_PreservesStatus(SlotStatus status) {
            // given
            AttractionSlot slot = createTomorrowSlot(10, status);
            ReflectionTestUtils.setField(slot, "autoClosed", false);
            given(attractionSlotRepository.tryReleaseCapacity(31L)).willReturn(1);
            givenSlot(slot);
            doAnswer(invocation -> {
                ReflectionTestUtils.setField(slot, "reservedCount", 9);
                return null;
            }).when(entityManager).refresh(slot);

            // when
            attractionSlotService.releaseReservationCapacity(31L);

            // then
            assertThat(slot.getReservedCount()).isEqualTo(9);
            assertThat(slot.getStatus()).isEqualTo(status);
            assertThat(slot.isAutoClosed()).isFalse();
            verify(entityManager).refresh(slot);
        }

        @Test
        @DisplayName("정원 복구 UPDATE가 실패하면 슬롯 조회와 갱신 없이 예외가 발생한다")
        void releaseReservationCapacity_UpdateFailed_ThrowsException() {
            // given
            given(attractionSlotRepository.tryReleaseCapacity(31L)).willReturn(0);

            // when & then
            assertErrorCode(() -> attractionSlotService.releaseReservationCapacity(31L),
                    ErrorCode.RESERVATION_CAPACITY_RESTORE_FAILED);
            verify(attractionSlotRepository, never()).findById(any());
            verifyNoInteractions(entityManager);
        }
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

        // ===== 시간 검증 예외 경로 =====

        // 예약 0명, 내일 14:00 OPEN 슬롯 + 어트랙션 조회까지 준비 (시간 검사 직전 단계까지 통과시키기 위함)
        private AttractionSlot givenModifiableSlotWithAttraction() {
            AttractionSlot slot = createTomorrowSlot(0, SlotStatus.OPEN);
            givenSlot(slot);
            given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(slot.getAttraction()));
            return slot;
        }

        @Test
        @DisplayName("실패: 과거 시작 시각 → SLOT_START_IN_PAST, 겹침 검사 안 함, 시간 그대로")
        void startInPast() {
            AttractionSlot slot = givenModifiableSlotWithAttraction();
            LocalDateTime yesterday = KstTime.now().toLocalDate().minusDays(1).atTime(14, 0);

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotTime(
                    31L, new UpdateAttractionSlotTimeRequest(yesterday)), ErrorCode.SLOT_START_IN_PAST);

            verify(attractionSlotRepository, never())
                    .countOverlappingSlotsExcludingSlotId(any(), any(), any(), any());
            assertThat(slot.getStartsAt()).isEqualTo(TOMORROW.atTime(14, 0));
            assertThat(slot.getEndsAt()).isEqualTo(TOMORROW.atTime(14, 10));
        }

        @Test
        @DisplayName("실패: 21:55 시작(종료 22:05) → SLOT_OUT_OF_OPERATING_HOURS, 겹침 검사 안 함, 시간 그대로")
        void outOfOperatingHours() {
            AttractionSlot slot = givenModifiableSlotWithAttraction();

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotTime(
                            31L, new UpdateAttractionSlotTimeRequest(TOMORROW.atTime(21, 55))),
                    ErrorCode.SLOT_OUT_OF_OPERATING_HOURS);

            verify(attractionSlotRepository, never())
                    .countOverlappingSlotsExcludingSlotId(any(), any(), any(), any());
            assertThat(slot.getStartsAt()).isEqualTo(TOMORROW.atTime(14, 0));
            assertThat(slot.getEndsAt()).isEqualTo(TOMORROW.atTime(14, 10));
        }

        @Test
        @DisplayName("실패: CANCELLED 슬롯 → SLOT_ALREADY_CANCELLED, 어트랙션 조회·겹침 검사 안 함")
        void cancelled() {
            AttractionSlot slot = createTomorrowSlot(0, SlotStatus.CANCELLED);
            givenSlot(slot);

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotTime(
                            31L, new UpdateAttractionSlotTimeRequest(TOMORROW.atTime(14, 30))),
                    ErrorCode.SLOT_ALREADY_CANCELLED);

            verifyNoInteractions(attractionRepository); // 취소 검사가 어트랙션 조회보다 먼저
            verify(attractionSlotRepository, never())
                    .countOverlappingSlotsExcludingSlotId(any(), any(), any(), any());
            assertThat(slot.getStartsAt()).isEqualTo(TOMORROW.atTime(14, 0));
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

        // 추가
        @Test
        @DisplayName("잔여가 있는 수동 CLOSED 슬롯: 정원을 늘려도 CLOSED 유지 (사용자에게 다시 노출 안 됨)")
        void manualClosedStaysClosed() {
            AttractionSlot slot = createTomorrowSlot(12, SlotStatus.OPEN);
            givenSlot(slot);

            // 1. 관리자가 상태 변경 API로 직접 마감
            attractionSlotService.updateAttractionSlotStatus(31L, new UpdateAttractionSlotStatusRequest("CLOSED"));

            // 2. 같은 슬롯의 정원을 늘림
            UpdateAttractionSlotCapacityResponse response =
                    attractionSlotService.updateAttractionSlotCapacity(31L, new UpdateAttractionSlotCapacityRequest(25));

            assertThat(response.status()).isEqualTo(SlotStatus.CLOSED);
            assertThat(response.remainingCount()).isEqualTo(13);
        }
    }

    // 슬로 상태 변경
    @Nested
    @DisplayName("슬롯 상태 변경")
    class UpdateStatus {

        @Test
        @DisplayName("성공: OPEN → SUSPENDED (예약 없음)")
        void success() {
            givenSlot(createTomorrowSlot(0, SlotStatus.OPEN));

            UpdateAttractionSlotStatusResponse response = attractionSlotService.updateAttractionSlotStatus(
                    31L, new UpdateAttractionSlotStatusRequest("SUSPENDED"));

            assertThat(response.previousStatus()).isEqualTo(SlotStatus.OPEN);
            assertThat(response.status()).isEqualTo(SlotStatus.SUSPENDED);
        }

        @Test
        @DisplayName("실패: 허용되지 않은 값(CANCELLED)")
        void invalidStatus() {
            assertErrorCode(() -> attractionSlotService.updateAttractionSlotStatus(
                    31L, new UpdateAttractionSlotStatusRequest("CANCELLED")), ErrorCode.INVALID_SLOT_STATUS);
        }

        @Test
        @DisplayName("실패: 현재 상태와 같음")
        void same() {
            givenSlot(createTomorrowSlot(0, SlotStatus.OPEN));

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotStatus(
                    31L, new UpdateAttractionSlotStatusRequest("OPEN")), ErrorCode.SLOT_STATUS_SAME);
        }

        @Test
        @DisplayName("실패: 잔여 0인데 OPEN으로")
        void openWhenFull() {
            givenSlot(createTomorrowSlot(20, SlotStatus.CLOSED));

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotStatus(
                    31L, new UpdateAttractionSlotStatusRequest("OPEN")), ErrorCode.SLOT_FULL);
        }

        @Test
        @DisplayName("실패: 예약이 있는데 SUSPENDED로")
        void suspendWithReservations() {
            givenSlot(createTomorrowSlot(3, SlotStatus.OPEN));

            assertErrorCode(() -> attractionSlotService.updateAttractionSlotStatus(
                    31L, new UpdateAttractionSlotStatusRequest("SUSPENDED")), ErrorCode.SLOT_HAS_RESERVATIONS);
        }
    }

    // 삭제(취소)
    @Nested
    @DisplayName("슬롯 삭제")
    class Delete {

        @Test
        @DisplayName("성공: 상태가 CANCELLED로 바뀜")
        void success() {
            AttractionSlot slot = createTomorrowSlot(0, SlotStatus.OPEN);
            givenSlot(slot);

            attractionSlotService.deleteAttractionSlot(31L);

            assertThat(slot.getStatus()).isEqualTo(SlotStatus.CANCELLED);
        }

        @Test
        @DisplayName("실패: 이미 취소됨")
        void alreadyCancelled() {
            givenSlot(createTomorrowSlot(0, SlotStatus.CANCELLED));

            assertErrorCode(() -> attractionSlotService.deleteAttractionSlot(31L), ErrorCode.SLOT_ALREADY_CANCELLED);
        }

        @Test
        @DisplayName("실패: 예약이 있음")
        void hasReservations() {
            givenSlot(createTomorrowSlot(1, SlotStatus.OPEN));

            assertErrorCode(() -> attractionSlotService.deleteAttractionSlot(31L), ErrorCode.SLOT_HAS_RESERVATIONS);
        }
    }
}
