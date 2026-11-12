package com.example.attractionreservation.infra.portone.controller;

import com.example.attractionreservation.global.response.ApiResponse;
import com.example.attractionreservation.infra.portone.config.PortOneProperties;
import com.example.attractionreservation.infra.portone.dto.PortOneConfigResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PortOneConfigController {
    private final PortOneProperties properties;

    @GetMapping("/api/config/portone")
    public ResponseEntity<ApiResponse<PortOneConfigResponse>> getConfig() {
        return ResponseEntity.ok(ApiResponse.ok(
                new PortOneConfigResponse(
                        properties.getStoreId(),
                        properties.getChannelKey()
                )
        ));
    }
}
