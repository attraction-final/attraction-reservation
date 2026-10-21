package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotResponse;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
        LocalDateTime endsAt = AttractionSlot.calculateEndsAt(startsAt, attraction.getDurationMinutes());
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
}
