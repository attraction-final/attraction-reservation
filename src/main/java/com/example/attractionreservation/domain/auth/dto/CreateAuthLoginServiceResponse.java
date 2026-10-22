
package com.example.attractionreservation.domain.auth.dto;

public record CreateAuthLoginServiceResponse(
        CreateAuthLoginResponse response,
        String refreshToken
) {
    public static CreateAuthLoginServiceResponse of(
            CreateAuthLoginResponse response,
            String refreshToken
    ) {
        return new CreateAuthLoginServiceResponse(response, refreshToken);
    }
}
