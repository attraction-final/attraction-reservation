package com.example.attractionreservation.domain.user.dto;

import com.example.attractionreservation.domain.user.entity.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
        @NotNull(message = "변경할 회원 상태는 필수입니다.")
        UserStatus status
) {
}