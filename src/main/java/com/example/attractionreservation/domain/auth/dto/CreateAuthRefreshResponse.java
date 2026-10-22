package com.example.attractionreservation.domain.auth.dto;

public record CreateAuthRefreshResponse(
        String accessToken,
        String tokenType,
        Long expiresIn
) {
    public static CreateAuthRefreshResponse of(
            String accessToken,
            Long expiresIn
    ) {
        return new CreateAuthRefreshResponse(
                accessToken,
                "Bearer",
                expiresIn
        );
    }
}