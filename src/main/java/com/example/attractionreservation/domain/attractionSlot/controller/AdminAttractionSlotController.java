package com.example.attractionreservation.domain.attractionSlot.controller;

import com.example.attractionreservation.domain.attractionSlot.dto.*;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminAttractionSlotController {

    private final AttractionSlotService attractionSlotService;

    // 슬롯 등록
    @PostMapping("/attractions/{attractionId}/slots")
    public ResponseEntity<ApiResponse<CreateAttractionSlotResponse>> createAttractionSlot(
            @PathVariable @Min(1) Long attractionId,
            @Valid @RequestBody CreateAttractionSlotRequest request) {

        CreateAttractionSlotResponse response = attractionSlotService.createAttractionSlot(attractionId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("슬롯 등록에 성공했습니다.", response));
    }

    // 관리자 날짜별 슬롯 조회
    @GetMapping("/attractions/{attractionId}/slots")
    public ResponseEntity<ApiResponse<SearchAdminAttractionSlotResponse>> getAdminAttractionSlotAll(
            @PathVariable @Min(1) Long attractionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, // 필수
            @RequestParam(defaultValue = "false") boolean includeCancelled) {

        SearchAdminAttractionSlotResponse response =
                attractionSlotService.findAdminAttractionSlotsByDate(attractionId, date, includeCancelled);
        return ResponseEntity.ok(ApiResponse.ok("슬롯 조회에 성공했습니다.", response));
    }

    // 슬롯 시간 수정
    @PatchMapping("/slots/{slotId}/time")
    public ResponseEntity<ApiResponse<UpdateAttractionSlotTimeResponse>> updateAttractionSlotTime(
            @PathVariable @Min(1) Long slotId,
            @Valid @RequestBody UpdateAttractionSlotTimeRequest request) {

        UpdateAttractionSlotTimeResponse response = attractionSlotService.updateAttractionSlotTime(slotId, request);
        return ResponseEntity.ok(ApiResponse.ok("슬롯 시간 수정에 성공했습니다.", response));
    }
}
