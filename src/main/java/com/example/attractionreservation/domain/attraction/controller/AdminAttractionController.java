package com.example.attractionreservation.domain.attraction.controller;

import com.example.attractionreservation.domain.attraction.dto.*;
import com.example.attractionreservation.domain.attraction.service.AttractionService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/attractions")
public class AdminAttractionController {

    private final AttractionService attractionService;

    // 어트랙션 등록
    // @RequestBody: 요청 본문의 JSON을 CreateAttractionRequest 객체로 변환
    // @Valid: 요청 객체에 선언된 검증 조건(@NotBlank, @Size 등)을 검사
    @PostMapping
    public ResponseEntity<ApiResponse<CreateAttractionResponse>> createAttraction(
            @Valid @RequestBody CreateAttractionRequest request) {

        CreateAttractionResponse response = attractionService.createAttraction(request); // 서비스에 요청DTO 전달해서 응답DTO로 반환
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("어트랙션 등록에 성공했습니다.", response));
    }

    // 어트랙션 수정
    @PatchMapping("/{attractionId}")
    public ResponseEntity<ApiResponse<SearchAttractionResponse>> updateAttraction(
            @PathVariable Long attractionId,
            @Valid @RequestBody UpdateAttractionRequest request) {

        SearchAttractionResponse response = attractionService.updateAttraction(attractionId, request);
        return ResponseEntity.ok(ApiResponse.ok("어트랙션 수정에 성공했습니다.", response));
    }

    // 어트랙션 운영 상태 변경
    @PatchMapping("/{attractionId}/status")
    public ResponseEntity<ApiResponse<UpdateAttractionStatusResponse>> updateAttractionStatus(
            @PathVariable Long attractionId,
            @Valid @RequestBody UpdateAttractionStatusRequest request) {

        UpdateAttractionStatusResponse response =
                attractionService.updateAttractionStatus(attractionId, request);
        return ResponseEntity.ok(ApiResponse.ok("어트랙션 운영 상태 변경에 성공했습니다.", response));
    }

    // 어트랙션 삭제
    @DeleteMapping("/{attractionId}")
    public ResponseEntity<ApiResponse<Void>> deleteAttraction(@PathVariable Long attractionId) {
        attractionService.deleteAttraction(attractionId);
        return ResponseEntity.ok(ApiResponse.<Void>ok("어트랙션 삭제에 성공했습니다.", null));
    }
}
