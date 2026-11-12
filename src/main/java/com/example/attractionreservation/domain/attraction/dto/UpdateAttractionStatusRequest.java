package com.example.attractionreservation.domain.attraction.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAttractionStatusRequest(

        @NotBlank
        String operationStatus
) {
}
