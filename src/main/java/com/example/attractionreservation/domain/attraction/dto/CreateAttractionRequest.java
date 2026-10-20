package com.example.attractionreservation.domain.attraction.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAttractionRequest(

        @NotBlank
        @Size(max = 50)
        String name,

        @NotBlank
        @Size(max = 200)
        String description,

        @NotBlank
        @Size(max = 50)
        String location,

        @NotNull
        @Min(1)
        Integer durationMinutes, // 이용 소요 시간(분 단위)

        // 추가: 필수, 1 이상
        @NotNull
        @Min(1)
        Integer entranceMoveMinutes
) {
}
