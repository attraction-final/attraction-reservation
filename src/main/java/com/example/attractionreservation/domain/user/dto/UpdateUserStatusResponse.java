package com.example.attractionreservation.domain.user.dto;

import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;

public record UpdateUserStatusResponse(
        Long id,
        String email,
        String nickname,
        UserStatus status
) {

    public static UpdateUserStatusResponse from(User user) {
        return new UpdateUserStatusResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getStatus()
        );
    }
}