package com.example.attractionreservation.domain.attraction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAttractionQuestionRequest(
        @NotBlank(message = "질문을 입력해 주세요.")
        @Size(max = 200, message = "질문은 200자 이하로 입력해 주세요.")
        String question
) {
}