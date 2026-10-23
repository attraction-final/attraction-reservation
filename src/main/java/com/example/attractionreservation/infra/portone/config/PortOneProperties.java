package com.example.attractionreservation.infra.portone.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

// 필수값이 없으면 애플리케이션 시작 시점에 실패한다.
@Getter @Setter
@Component
@Validated
@ConfigurationProperties(prefix = "portone")
public class PortOneProperties {
    @NotBlank
    private String baseUrl;
    @NotBlank
    private String apiSecret;
    @NotBlank
    private String storeId;
    @NotBlank
    private String channelKey;
    @NotBlank
    private String webhookSecret;
}
