package com.example.attractionreservation.domain.attractionSlot.controller;

import com.example.attractionreservation.domain.attractionSlot.dto.SearchAttractionSlotResponse;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/attractions")
public class AttractionSlotController {

    private final AttractionSlotService attractionSlotService;

    // 사용자 날짜별 슬롯 조회
    @GetMapping("/{attractionId}/slots")
    public ResponseEntity<ApiResponse<SearchAttractionSlotResponse>> getAttractionSlotAll(
            @PathVariable @Min(1) Long attractionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        SearchAttractionSlotResponse response = attractionSlotService.findAttractionSlotsByDate(attractionId, date);
        return ResponseEntity.ok(ApiResponse.ok("슬롯 조회에 성공했습니다.", response));
    }
}
