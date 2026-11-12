package com.example.attractionreservation.domain.auth.oauth2;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

public enum SocialLoginError {

    // 카카오·구글 이외의 제공자로 요청한 경우
    UNSUPPORTED_PROVIDER,

    // 제공자의 고유 회원 ID가 없거나 유효하지 않은 경우
    INVALID_PROVIDER_USER_ID,

    // 제공자로부터 이메일을 받지 못한 경우
    EMAIL_REQUIRED,

    // 제공자의 이메일 검증 조건을 충족하지 못한 경우
    EMAIL_NOT_VERIFIED,

    // 동일 이메일의 기존 회원이 있어 자동 계정 연결을 제한하는 경우
    EMAIL_ALREADY_EXISTS,

    // 기존 소셜 회원이 ACTIVE 상태가 아닌 경우
    USER_NOT_ACTIVE,

    // 회원 정보가 저장 가능한 형식이나 길이 조건에 맞지 않는 경우
    INVALID_USER_INFO,

    // 그 밖의 인증 실패에 사용하는 공통 코드
    AUTHENTICATION_FAILED;

    public OAuth2AuthenticationException toException() {
        return new OAuth2AuthenticationException(
                new OAuth2Error(name())
        );
    }
}
