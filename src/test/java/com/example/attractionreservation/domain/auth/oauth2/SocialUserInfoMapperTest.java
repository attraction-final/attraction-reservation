package com.example.attractionreservation.domain.auth.oauth2;

import com.example.attractionreservation.domain.user.entity.AuthProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialUserInfoMapperTest {

    private final SocialUserInfoMapper mapper = new SocialUserInfoMapper();

    @Test
    @DisplayName("카카오의 중첩 응답과 숫자 회원 ID를 변환한다")
    void kakao_mapsNestedAttributes() {
        var info = mapper.map("kakao", Map.of(
                "id", 123L,
                "kakao_account", Map.of(
                        "email", "kakao@example.com",
                        "is_email_valid", true,
                        "is_email_verified", true,
                        "profile", Map.of("nickname", "카카오회원")
                )
        ));

        assertThat(info.provider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(info.providerUserId()).isEqualTo("123");
        assertThat(info.email()).isEqualTo("kakao@example.com");
        assertThat(info.emailVerified()).isTrue();
        assertThat(info.nickname()).isEqualTo("카카오회원");
    }

    @Test
    @DisplayName("구글 회원 식별에는 이메일 대신 sub를 사용한다")
    void google_usesSubjectAsIdentity() {
        var info = mapper.map("google", Map.of(
                "sub", "google-123",
                "email", "google@example.com",
                "email_verified", true,
                "name", "구글회원"
        ));

        assertThat(info.provider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(info.providerUserId()).isEqualTo("google-123");
        assertThat(info.email()).isEqualTo("google@example.com");
        assertThat(info.emailVerified()).isTrue();
        assertThat(info.nickname()).isEqualTo("구글회원");
    }

    @Test
    @DisplayName("카카오 선택 정보가 없으면 누락된 값으로 변환한다")
    void kakao_missingAccount_doesNotFailDuringMapping() {
        var info = mapper.map("kakao", Map.of("id", 123L));

        // 이메일 누락에 따른 가입 제한은 회원 식별 서비스에서 판단한다.
        assertThat(info.providerUserId()).isEqualTo("123");
        assertThat(info.email()).isNull();
        assertThat(info.emailVerified()).isFalse();
        assertThat(info.nickname()).isNull();
    }

    @Test
    @DisplayName("지원하지 않는 제공자는 정해진 오류 코드로 거부한다")
    void unsupportedProvider_rejected() {
        assertThatThrownBy(() -> mapper.map("unknown", Map.of()))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode()
                        ).isEqualTo("UNSUPPORTED_PROVIDER")
                );
    }

    @ParameterizedTest
    @CsvSource({
            "true, false",
            "false, true",
            "false, false"
    })
    @DisplayName("카카오 이메일은 유효성과 인증 여부가 모두 참이어야 한다")
    void kakao_invalidOrUnverifiedEmail_returnsFalse(
            boolean valid,
            boolean verified
    ) {
        var info = mapper.map("kakao", Map.of(
                "id", 123L,
                "kakao_account", Map.of(
                        "email", "user@example.com",
                        "is_email_valid", valid,
                        "is_email_verified", verified
                )
        ));

        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("구글의 미검증 이메일을 검증된 것으로 처리하지 않는다")
    void google_unverifiedEmail_returnsFalse() {
        var info = mapper.map("google", Map.of(
                "sub", "google-123",
                "email", "user@example.com",
                "email_verified", false
        ));

        assertThat(info.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("카카오 회원 ID가 숫자가 아니면 유효한 식별자로 사용하지 않는다")
    void kakao_nonNumericId_returnsNull() {
        var info = mapper.map("kakao", Map.of(
                "id", "unexpected-id"
        ));

        assertThat(info.providerUserId()).isNull();
    }
}