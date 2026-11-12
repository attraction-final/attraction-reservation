package com.example.attractionreservation.domain.auth.oauth2;

import com.example.attractionreservation.domain.auth.dto.SocialUserInfo;
import com.example.attractionreservation.domain.user.entity.AuthProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SocialUserInfoMapper {

    public SocialUserInfo map(
            String registrationId,
            Map<String, Object> attributes
    ) {
        return switch (registrationId) {
            case "kakao" -> fromKakao(attributes);
            case "google" -> fromGoogle(attributes);
            default -> throw SocialLoginError.UNSUPPORTED_PROVIDER.toException();
        };
    }

    private SocialUserInfo fromKakao(Map<String, Object> attributes) {
        Map<?, ?> account = nestedMap(attributes.get("kakao_account"));
        Map<?, ?> profile = nestedMap(account.get("profile"));

        // 카카오 회원 ID는 숫자로 제공되므로 문자열로 변환한다.
        Object id = attributes.get("id");

        return new SocialUserInfo(
                AuthProvider.KAKAO,
                id instanceof Number ? id.toString() : null,
                stringValue(account.get("email")),
                Boolean.TRUE.equals(account.get("is_email_valid"))
                        && Boolean.TRUE.equals(account.get("is_email_verified")),
                stringValue(profile.get("nickname"))
        );
    }

    private SocialUserInfo fromGoogle(Map<String, Object> attributes) {
        // 이메일은 변경될 수 있으므로 고유 식별자인 sub를 사용한다.
        return new SocialUserInfo(
                AuthProvider.GOOGLE,
                stringValue(attributes.get("sub")),
                stringValue(attributes.get("email")),
                Boolean.TRUE.equals(attributes.get("email_verified")),
                stringValue(attributes.get("name"))
        );
    }

    private Map<?, ?> nestedMap(Object value) {
        // 선택 정보가 없거나 예상한 자료형이 아니면 빈 값으로 처리한다.
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}