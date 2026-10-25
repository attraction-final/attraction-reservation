package com.example.attractionreservation.domain.user.dto;

import com.example.attractionreservation.domain.user.entity.User;

public record UpdateUserResponse(
        Long id,
        String email,
        String nickname
) {

    public static UpdateUserResponse from(User user) {
        return new UpdateUserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname()
        );
    }
}