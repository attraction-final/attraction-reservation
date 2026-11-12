package com.example.attractionreservation.domain.user.dto;

import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;

import java.time.LocalDateTime;
import java.time.ZoneId;

public record SearchUserResponse(
        Long id,
        String email,
        String nickname,
        Role role,
        UserStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static SearchUserResponse from(User user) {
        ZoneId zoneId = ZoneId.of("Asia/Seoul");

        return new SearchUserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole(),
                user.getStatus(),
                // 저장된 시각을 공통 규칙에 따라 한국 시각으로 표현한다.
                LocalDateTime.ofInstant(user.getCreatedAt(), zoneId),
                LocalDateTime.ofInstant(user.getUpdatedAt(), zoneId)
        );
    }
}