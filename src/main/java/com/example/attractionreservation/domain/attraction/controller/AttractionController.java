package com.example.attractionreservation.domain.attraction.controller;

import com.example.attractionreservation.domain.attraction.dto.SearchAttractionCondition;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionListResponse;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionResponse;
import com.example.attractionreservation.domain.attraction.service.AttractionService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/attractions")
// 누구나 조회 가능
public class AttractionController {

    private final AttractionService attractionService;

    // 어트랙션 목록 조회
    @GetMapping
    public ResponseEntity<ApiResponse<SearchAttractionListResponse>> getAttractionAll(
            @Valid @ModelAttribute SearchAttractionCondition condition) {

        SearchAttractionListResponse response = attractionService.findAttractionAll(condition);
        return ResponseEntity.ok(ApiResponse.ok("어트랙션 목록 조회에 성공했습니다.", response));
    }

    // 어트랙션 상세 조회
    @GetMapping("/{attractionId}")
    public ResponseEntity<ApiResponse<SearchAttractionResponse>> getAttractionById(
            @PathVariable Long attractionId) {

        SearchAttractionResponse response = attractionService.findAttractionById(attractionId);
        return ResponseEntity.ok(ApiResponse.ok("어트랙션 상세 조회에 성공했습니다.", response));
    }
}
