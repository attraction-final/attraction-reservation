package com.example.attractionreservation.domain.attraction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateAttractionRequest(

        @Size(max = 50)
        @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
        String name,

        @Size(max = 50)
        @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
        String description,

        @Size(max = 50)
        @Pattern(regexp = ".*\\S.*", message = "공백만 입력할 수 없습니다.")
        String location,

        @Min(1)
        Integer durationMinutes
) {
}
