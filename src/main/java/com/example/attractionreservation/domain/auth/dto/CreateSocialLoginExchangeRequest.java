package com.example.attractionreservation.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSocialLoginExchangeRequest(

        // 제공자 인가 코드가 아니라 우리 서버가 발급한 일회용 로그인 코드다.
        @NotBlank(message = "일회용 로그인 코드는 필수입니다.")
        String code
) {
}