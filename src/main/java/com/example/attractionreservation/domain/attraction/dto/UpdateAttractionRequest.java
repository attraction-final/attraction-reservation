package com.example.attractionreservation.domain.attraction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateAttractionRequest(

        @Size(max = 50)
        @Pattern(regexp = "(?s).*\\S.*", message = "공백만 입력할 수 없습니다.")
        String name,

        @Size(max = 200)
        @Pattern(regexp = "(?s).*\\S.*", message = "공백만 입력할 수 없습니다.")
        String description,

        @Size(max = 50)
        @Pattern(regexp = "(?s).*\\S.*", message = "공백만 입력할 수 없습니다.")
        String location,

        @Min(1)
        Integer durationMinutes,

        // 추가: 안 보내도 됨(null), 보내면 1 이상
        @Min(1)
        Integer entranceMoveMinutes
) {
}
