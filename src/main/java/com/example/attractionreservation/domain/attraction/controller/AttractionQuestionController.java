package com.example.attractionreservation.domain.attraction.controller;

import com.example.attractionreservation.domain.attraction.dto.CreateAttractionQuestionRequest;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionQuestionResponse;
import com.example.attractionreservation.domain.attraction.service.AttractionQuestionService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/attractions/questions")
public class AttractionQuestionController {

    private final AttractionQuestionService attractionQuestionService;

    //어트랙션 질문하기
    //의사 결정 -> 되묻기 미지원도 질문이 정상 처리된 결과이므로 200으로 내려주고 data.type으로 구분
    @PostMapping
    public ResponseEntity<ApiResponse<CreateAttractionQuestionResponse>>
    createAttractionQuestion(@Valid @RequestBody CreateAttractionQuestionRequest request) {
        CreateAttractionQuestionResponse response = attractionQuestionService.answer(request.question());
        return ResponseEntity.ok(ApiResponse.ok("어트랙션 질문 처리에 성공했습니다", response));
    }

}