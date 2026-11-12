package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.dto.SocialUserInfo;
import com.example.attractionreservation.domain.auth.oauth2.SocialLoginError;
import com.example.attractionreservation.domain.user.entity.AuthProvider;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SocialMemberService {

    private final UserRepository userRepository;

    @Transactional
    public Long findOrCreate(SocialUserInfo info) {
        validateIdentity(info);

        var existingUser = userRepository.findByProviderAndProviderUserId(
                info.provider(),
                info.providerUserId()
        );

        if (existingUser.isPresent()) {
            User user = existingUser.get();

            // 기존 회원은 이메일 대신 제공자와 고유 회원 ID로 식별한다.
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw SocialLoginError.USER_NOT_ACTIVE.toException();
            }

            return user.getId();
        }

        return createMember(info);
    }

    private Long createMember(SocialUserInfo info) {
        String email = info.email();

        // 신규 가입에는 이메일이 필요하며, 기존 회원과 자동 연결하지 않는다.
        if (email == null || email.isBlank()) {
            throw SocialLoginError.EMAIL_REQUIRED.toException();
        }

        // 이메일은 있지만 제공자가 인증하지 않은 경우 가입을 제한한다.
        if (!info.emailVerified()) {
            throw SocialLoginError.EMAIL_NOT_VERIFIED.toException();
        }

        if (email.length() > 50) {
            throw SocialLoginError.INVALID_USER_INFO.toException();
        }

        if (userRepository.existsByEmail(email)) {
            throw SocialLoginError.EMAIL_ALREADY_EXISTS.toException();
        }

        User user = User.createSocial(
                email,
                resolveNickname(info.nickname()),
                info.provider(),
                info.providerUserId()
        );

        // DB 제약 조건 위반을 트랜잭션 종료 이전에 확인한다.
        return userRepository.saveAndFlush(user).getId();
    }

    private void validateIdentity(SocialUserInfo info) {
        if (info == null) {
            throw SocialLoginError.INVALID_USER_INFO.toException();
        }

        if (info.provider() != AuthProvider.KAKAO
                && info.provider() != AuthProvider.GOOGLE) {
            throw SocialLoginError.UNSUPPORTED_PROVIDER.toException();
        }

        String providerUserId = info.providerUserId();

        if (providerUserId == null
                || providerUserId.isBlank()
                || providerUserId.length() > 255) {
            throw SocialLoginError.INVALID_PROVIDER_USER_ID.toException();
        }
    }

    private String resolveNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            // 제공자가 닉네임을 주지 않으면 개인정보를 사용하지 않고 생성한다.
            return "회원_" + UUID.randomUUID().toString().replace("-", "");
        }

        String value = nickname.strip();

        // 제공자의 긴 닉네임은 DB 길이에 맞추되 이모지 문자가 잘리지 않게 처리한다.
        int length = value.codePointCount(0, value.length());
        if (length > 50) {
            return value.substring(0, value.offsetByCodePoints(0, 50));
        }

        return value;
    }
}