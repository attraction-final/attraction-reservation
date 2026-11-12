package com.example.attractionreservation.domain.auth.dto;

public record CreateAuthLoginResponse(
        String accessToken,
        String tokenType,
        Long expiresIn
) {
    public static CreateAuthLoginResponse of(String accessToken, Long expiresIn) {
        return new CreateAuthLoginResponse(accessToken, "Bearer", expiresIn);
    }
}