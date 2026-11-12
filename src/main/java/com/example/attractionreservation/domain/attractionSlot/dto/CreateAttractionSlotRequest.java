package com.example.attractionreservation.domain.attractionSlot.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record CreateAttractionSlotRequest(

        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startsAt, // 탑승 시작 일시 (종료 일시는 서버가 계산)

        @NotNull
        @Min(1)
        Integer capacity
) {
}
