package com.example.attractionreservation.domain.user.dto;

import com.example.attractionreservation.domain.user.entity.User;

public record GetUserResponse(
        Long id,
        String email,
        String nickname,
        String role,
        String status
) {

    public static GetUserResponse from(User user) {
        return new GetUserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole().name(),
                user.getStatus().name()
        );
    }
}