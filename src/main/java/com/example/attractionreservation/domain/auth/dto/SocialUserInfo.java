package com.example.attractionreservation.domain.auth.dto;

import com.example.attractionreservation.domain.user.entity.AuthProvider;

// 제공자마다 다른 응답을 회원 식별 서비스에서 사용할 공통 형태로 변환한다.
public record SocialUserInfo(
        AuthProvider provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String nickname
) {
}