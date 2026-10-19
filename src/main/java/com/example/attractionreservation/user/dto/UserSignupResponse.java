package com.example.attractionreservation.user.dto;

public record UserSignupResponse(
        Long id,
        String email,
        String nickname
) {
}