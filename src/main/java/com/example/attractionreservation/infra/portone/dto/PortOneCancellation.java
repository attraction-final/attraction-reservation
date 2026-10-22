package com.example.attractionreservation.infra.portone.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOneCancellation(
        String id,
        String status
) {
}
