package com.example.attractionreservation.domain.user.event;

import com.example.attractionreservation.domain.user.entity.UserStatus;

// 관리자 회원 상태 변경 로그에 필요한 정보를 보관한다.
public record UserStatusChangedEvent(
        Long adminId,
        Long userId,
        UserStatus previousStatus,
        UserStatus currentStatus
) {
}