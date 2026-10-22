package com.example.attractionreservation.infra.portone.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PortOneCancelRequest(
        String reason,
        String storeId,
        Long amount
) {
    // 전체 취소
    public PortOneCancelRequest(String reason, String storeId) {
        this(reason, storeId, null);
    }
}
