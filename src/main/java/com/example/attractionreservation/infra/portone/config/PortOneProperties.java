package com.example.attractionreservation.infra.portone.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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
    // 취소가 IDEMPOTENCY_OUTSTANDING_REQUEST(같은 멱등 키의 앞선 요청 처리 중)로 응답하면 이 시간의 1배, 2배를 기다려 재시도한다.
    // 음수면 Thread.sleep이 예외를 던져 실패로 기록되고, 다음 재시도가 새 키로 취소를 보내게 된다.
    // 상한(10초)은 PG 취소 최대 소요 시간(통신 45초 + 대기 1배·2배 30초 = 75초)이 멈춘 행 재점유 기준(5분)보다 짧도록 둔다.
    // 길어지면 처리 중인 행을 멈춘 것으로 보고 다른 처리가 동시에 점유한다.
    @NotNull
    @DurationMin(millis = 0)
    @DurationMax(seconds = 10)
    private Duration cancelOutstandingRetryDelay = Duration.ofSeconds(1);
}
