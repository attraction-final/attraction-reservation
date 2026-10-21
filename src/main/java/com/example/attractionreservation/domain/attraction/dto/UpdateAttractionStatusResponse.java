package com.example.attractionreservation.domain.attraction.dto;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.global.util.KstTime;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record UpdateAttractionStatusResponse(
        Long attractionId,
        OperationStatus previousStatus,
        OperationStatus operationStatus,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {

    public static UpdateAttractionStatusResponse of(Attraction attraction,
                                                    OperationStatus previousStatus) {
        return new UpdateAttractionStatusResponse(
                attraction.getId(),
                previousStatus,
                attraction.getOperationStatus(),
                KstTime.toLocalDateTime(attraction.getUpdatedAt())
        );
    }
}

