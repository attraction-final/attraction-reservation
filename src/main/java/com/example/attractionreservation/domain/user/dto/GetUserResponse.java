package com.example.attractionreservation.domain.user.dto;

import com.example.attractionreservation.domain.user.entity.User;

public record GetUserResponse(
        Long id,
        String email,
        String nickname,
        String role,
        String status,
        // 가입 방식: LOCAL(이메일 가입) | KAKAO | GOOGLE
        // 프론트가 소셜 회원에게 비밀번호 변경 버튼을 숨기는 데 사용한다.
        String provider
) {

    public static GetUserResponse from(User user) {
        return new GetUserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getProvider().name()
        );
    }
}