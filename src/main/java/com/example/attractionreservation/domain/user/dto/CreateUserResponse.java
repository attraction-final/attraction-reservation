package com.example.attractionreservation.domain.user.dto;

public record CreateUserResponse(
        Long id,
        String email,
        String nickname
) {
}