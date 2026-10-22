package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.dto.*;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot.calculateEndsAt;

@Service
@RequiredArgsConstructor
public class AttractionSlotService {

    private final AttractionSlotRepository attractionSlotRepository;
    private final AttractionRepository attractionRepository;

    // 슬롯 등록
    @Transactional
    public CreateAttractionSlotResponse createAttractionSlot(Long attractionId, CreateAttractionSlotRequest request) {
        // 1. 삭제되지 않은 어트랙션 조회
        Attraction attraction = findActiveAttraction(attractionId);

        // 2. 종료 시각 계산 후 시간 규칙 검사
        LocalDateTime startsAt = request.startsAt();
        LocalDateTime endsAt = calculateEndsAt(startsAt, attraction.getDurationMinutes());
        validateSlotTime(startsAt, endsAt, KstTime.now());

        // 3. 같은 어트랙션의 다른 슬롯과 겹치는지 검사 (BR-SLT-02)
        if (attractionSlotRepository.countOverlappingSlots(attractionId, startsAt, endsAt) > 0) {
            throw new BusinessException(ErrorCode.SLOT_TIME_OVERLAP);
        }

        // 4. 저장 (상태 OPEN, 예약 인원 0으로 시작)
        AttractionSlot savedSlot = attractionSlotRepository.save(
                AttractionSlot.create(attraction, startsAt, request.capacity()));
        return CreateAttractionSlotResponse.from(savedSlot);
    }

    private Attraction findActiveAttraction(Long attractionId) {
        return attractionRepository.findByIdAndDeletedAtIsNull(attractionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND));
    }

    // 등록·시간 수정 공통 시간 검사
    private void validateSlotTime(LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime now) {
        if (!startsAt.isAfter(now)) { // 현재보다 미래여야 함
            throw new BusinessException(ErrorCode.SLOT_START_IN_PAST);
        }
        if (!AttractionSlot.isWithinOperatingHours(startsAt, endsAt)) { // BR-SLT-03
            throw new BusinessException(ErrorCode.SLOT_OUT_OF_OPERATING_HOURS);
        }
    }

    // 사용자 날짜별 조회
    @Transactional(readOnly = true)
    public SearchAttractionSlotResponse findAttractionSlotsByDate(Long attractionId, LocalDate date) {
        Attraction attraction = findActiveAttraction(attractionId);
        LocalDateTime now = KstTime.now();
        LocalDate targetDate = (date != null) ? date : now.toLocalDate(); // 날짜가 없으면 오늘

        List<AttractionSlot> slots = findSlotsOfDay(attractionId, targetDate, false); // 취소 슬롯은 숨김
        return SearchAttractionSlotResponse.of(attraction, targetDate, slots, now);
    }

    // 하루 범위: [그날 00:00, 다음날 00:00)
    private List<AttractionSlot> findSlotsOfDay(Long attractionId, LocalDate date, boolean includeCancelled) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime nextDayStart = date.plusDays(1).atStartOfDay();
        return attractionSlotRepository.findSlotsByAttractionIdAndDate(
                attractionId, dayStart, nextDayStart, includeCancelled);
    }

    // 관리자 날짜별 조회
    @Transactional(readOnly = true)
    public SearchAdminAttractionSlotResponse findAdminAttractionSlotsByDate(Long attractionId, LocalDate date,
                                                                            boolean includeCancelled) {
        Attraction attraction = findActiveAttraction(attractionId);
        List<AttractionSlot> slots = findSlotsOfDay(attractionId, date, includeCancelled);
        return SearchAdminAttractionSlotResponse.of(attraction, date, slots);
    }

    // 슬롯 시간 수정
    @Transactional
    public UpdateAttractionSlotTimeResponse updateAttractionSlotTime(Long slotId,
                                                                     UpdateAttractionSlotTimeRequest request) {
        AttractionSlot slot = findSlot(slotId);
        LocalDateTime now = KstTime.now();
        validateModifiable(slot, now);

        if (slot.hasReservations()) { // BR-SLT-06
            throw new BusinessException(ErrorCode.SLOT_HAS_RESERVATIONS);
        }

        // 종료 시각 계산에 어트랙션 소요 시간이 필요
        Long attractionId = slot.getAttraction().getId();
        Attraction attraction = findActiveAttraction(attractionId);

        LocalDateTime newStartsAt = request.startsAt();
        LocalDateTime newEndsAt = calculateEndsAt(newStartsAt, attraction.getDurationMinutes());
        validateSlotTime(newStartsAt, newEndsAt, now);

        // 자기 자신은 빼고 겹침 검사
        if (attractionSlotRepository.countOverlappingSlotsExcludingSlotId(
                attractionId, slotId, newStartsAt, newEndsAt) > 0) {
            throw new BusinessException(ErrorCode.SLOT_TIME_OVERLAP);
        }

        slot.changeTime(newStartsAt); // 변경 감지(dirty checking)로 커밋 시 UPDATE
        return UpdateAttractionSlotTimeResponse.from(slot);
    }

    private AttractionSlot findSlot(Long slotId) {
        return attractionSlotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SLOT_NOT_FOUND));
    }

    // 수정·상태 변경·삭제 공통 검사: 취소된 슬롯, 종료된 슬롯은 건드릴 수 없음
    private void validateModifiable(AttractionSlot slot, LocalDateTime now) {
        if (slot.isCancelled()) {
            throw new BusinessException(ErrorCode.SLOT_ALREADY_CANCELLED);
        }
        if (slot.isEnded(now)) {
            throw new BusinessException(ErrorCode.SLOT_ALREADY_ENDED);
        }
    }

    // 슬롯 정원 수정
    @Transactional
    public UpdateAttractionSlotCapacityResponse updateAttractionSlotCapacity(Long slotId,
                                                                             UpdateAttractionSlotCapacityRequest request) {
        AttractionSlot slot = findSlot(slotId);
        validateModifiable(slot, KstTime.now());

        if (request.capacity() < slot.getReservedCount()) { // BR-SLT-05
            throw new BusinessException(ErrorCode.SLOT_CAPACITY_BELOW_RESERVED);
        }

        slot.changeCapacity(request.capacity()); // 잔여 정원에 맞춰 OPEN/CLOSED 자동 조정
        return UpdateAttractionSlotCapacityResponse.from(slot);
    }

    // 슬롯 상태 변경
    @Transactional
    public UpdateAttractionSlotStatusResponse updateAttractionSlotStatus(Long slotId,
                                                                         UpdateAttractionSlotStatusRequest request) {
        SlotStatus newStatus = SlotStatus.from(request.status()); // 허용되지 않은 값이면 400
        AttractionSlot slot = findSlot(slotId);
        validateModifiable(slot, KstTime.now());

        SlotStatus previousStatus = slot.getStatus();
        if (previousStatus == newStatus) {
            throw new BusinessException(ErrorCode.SLOT_STATUS_SAME);
        }
        if (newStatus == SlotStatus.OPEN && slot.isFull()) {
            throw new BusinessException(ErrorCode.SLOT_FULL, "잔여 정원이 없어 OPEN으로 바꿀 수 없습니다.");
        }
        if (newStatus == SlotStatus.SUSPENDED && slot.hasReservations()) {
            throw new BusinessException(ErrorCode.SLOT_HAS_RESERVATIONS);
        }

        slot.changeStatus(newStatus);
        return UpdateAttractionSlotStatusResponse.of(slot, previousStatus);
    }


}
