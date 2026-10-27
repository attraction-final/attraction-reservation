package com.example.attractionreservation.domain.attractionSlot.entity;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.UnavailableReason;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AttractionSlotTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    private Attraction createAttraction() {
        Attraction attraction = Attraction.create("롤러코스터", "빠른 코스터", "A구역", 10, 8);
        ReflectionTestUtils.setField(attraction, "id", 1L);
        return attraction;
    }

    private AttractionSlot createSlot(int capacity, int reservedCount, SlotStatus status) {
        AttractionSlot slot = AttractionSlot.create(createAttraction(), DAY.atTime(14, 0), capacity);
        ReflectionTestUtils.setField(slot, "reservedCount", reservedCount);
        ReflectionTestUtils.setField(slot, "status", status);
        return slot;
    }

    @Test
    @DisplayName("생성 시 종료 시각은 시작+소요시간, 상태 OPEN, 예약 0명")
    void create() {
        AttractionSlot slot = AttractionSlot.create(createAttraction(), DAY.atTime(14, 0), 20);

        assertThat(slot.getEndsAt()).isEqualTo(DAY.atTime(14, 10));
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(slot.getReservedCount()).isZero();
        assertThat(slot.getRemainingCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("운영 시간 판단: 10:00 시작·22:00 종료는 허용, 벗어나면 거부")
    void isWithinOperatingHours() {
        assertThat(AttractionSlot.isWithinOperatingHours(DAY.atTime(10, 0), DAY.atTime(10, 10))).isTrue();
        assertThat(AttractionSlot.isWithinOperatingHours(DAY.atTime(21, 50), DAY.atTime(22, 0))).isTrue();
        assertThat(AttractionSlot.isWithinOperatingHours(DAY.atTime(9, 59), DAY.atTime(10, 9))).isFalse();
        assertThat(AttractionSlot.isWithinOperatingHours(DAY.atTime(21, 55), DAY.atTime(22, 5))).isFalse();
        assertThat(AttractionSlot.isWithinOperatingHours(DAY.atTime(23, 55), DAY.plusDays(1).atTime(0, 5))).isFalse();
    }

    @Test
    @DisplayName("예약 불가 사유: 어트랙션 운영 안 함이 가장 먼저")
    void unavailableReason_attractionNotOperating() {
        AttractionSlot slot = createSlot(20, 0, SlotStatus.SUSPENDED);
        assertThat(slot.findUnavailableReason(false, DAY.atTime(9, 0)))
                .isEqualTo(UnavailableReason.ATTRACTION_NOT_OPERATING);
    }

    @Test
    @DisplayName("예약 불가 사유: 슬롯 운영 중단")
    void unavailableReason_suspended() {
        AttractionSlot slot = createSlot(20, 0, SlotStatus.SUSPENDED);
        assertThat(slot.findUnavailableReason(true, DAY.atTime(9, 0))).isEqualTo(UnavailableReason.SUSPENDED);
    }

    @Test
    @DisplayName("예약 마감: 13:30 정각은 예약 가능, 13:30:01은 마감")
    void unavailableReason_deadline() {
        AttractionSlot slot = createSlot(20, 0, SlotStatus.OPEN);
        assertThat(slot.findUnavailableReason(true, DAY.atTime(13, 30))).isNull();
        assertThat(slot.findUnavailableReason(true, DAY.atTime(13, 30, 1)))
                .isEqualTo(UnavailableReason.DEADLINE_PASSED);
    }

    @Test
    @DisplayName("예약 불가 사유: 잔여 정원 없음")
    void unavailableReason_full() {
        AttractionSlot slot = createSlot(20, 20, SlotStatus.CLOSED);
        assertThat(slot.findUnavailableReason(true, DAY.atTime(9, 0))).isEqualTo(UnavailableReason.FULL);
    }

    @Test
    @DisplayName("종료 판단: 종료 시각과 같거나 지나면 종료")
    void isEnded() {
        AttractionSlot slot = createSlot(20, 0, SlotStatus.OPEN);
        assertThat(slot.isEnded(DAY.atTime(14, 9))).isFalse();
        assertThat(slot.isEnded(DAY.atTime(14, 10))).isTrue();
    }

    @Test
    @DisplayName("시간 변경 시 종료 시각도 다시 계산")
    void changeTime() {
        AttractionSlot slot = createSlot(20, 0, SlotStatus.OPEN);
        slot.changeTime(DAY.atTime(15, 0));
        assertThat(slot.getStartsAt()).isEqualTo(DAY.atTime(15, 0));
        assertThat(slot.getEndsAt()).isEqualTo(DAY.atTime(15, 10));
    }

    @Test
    @DisplayName("정원을 예약 인원과 같게 줄이면 OPEN → CLOSED")
    void changeCapacity_toFull() {
        AttractionSlot slot = createSlot(20, 12, SlotStatus.OPEN);
        slot.changeCapacity(12);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.CLOSED);
    }

    @Test
    @DisplayName("정원 소진으로 자동 마감된 슬롯은 정원을 늘리면 CLOSED → OPEN")
    void changeCapacity_reopen() {
        AttractionSlot slot = createSlot(20, 12, SlotStatus.OPEN);
        slot.changeCapacity(12); // 잔여 0 → 자동 마감
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.CLOSED);

        slot.changeCapacity(15); // 잔여 3 → 자동 재오픈

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(slot.getRemainingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("운영 중단 슬롯은 정원을 바꿔도 상태 유지")
    void changeCapacity_suspendedStays() {
        AttractionSlot slot = createSlot(20, 0, SlotStatus.SUSPENDED);
        slot.changeCapacity(30);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.SUSPENDED);
    }

    @Test
    @DisplayName("SlotStatus.from: CANCELLED와 잘못된 값은 거부")
    void slotStatusFrom() {
        assertThat(SlotStatus.from("SUSPENDED")).isEqualTo(SlotStatus.SUSPENDED);
        assertThatThrownBy(() -> SlotStatus.from("CANCELLED"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_SLOT_STATUS);
        assertThatThrownBy(() -> SlotStatus.from("open"))
                .isInstanceOf(BusinessException.class);
    }

    // 수동 마감 추가
    @Test
    @DisplayName("잔여가 있는 수동 CLOSED 슬롯은 정원을 바꿔도 CLOSED 유지")
    void changeCapacity_manualClosedStays() {
        AttractionSlot slot = createSlot(20, 5, SlotStatus.OPEN);
        slot.changeStatus(SlotStatus.CLOSED); // 관리자가 직접 마감 (잔여 15)

        slot.changeCapacity(30);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.CLOSED);
        assertThat(slot.getRemainingCount()).isEqualTo(25);
    }

    @Test
    @DisplayName("자동 마감 후 관리자가 상태를 바꿨다가 다시 CLOSED로 하면 수동 마감으로 취급")
    void changeStatus_overridesAutoClosed() {
        AttractionSlot slot = createSlot(20, 12, SlotStatus.OPEN);
        slot.changeCapacity(12);              // 자동 마감
        slot.changeStatus(SlotStatus.SUSPENDED);
        slot.changeStatus(SlotStatus.CLOSED); // 관리자가 직접 마감

        slot.changeCapacity(20);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.CLOSED);
    }
}
