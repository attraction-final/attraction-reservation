
package com.example.attractionreservation.domain.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private JwtProvider jwtProvider;

    private static final long ACCESS_EXPIRATION = 600_000L;       // 10분
    private static final long REFRESH_EXPIRATION = 604_800_000L; // 7일

    private static final String SECRET = Base64.getEncoder()
            .encodeToString(
                    "jwt-provider-test-secret-key-longer-than-32-bytes"
                            .getBytes(StandardCharsets.UTF_8)
            );

    @BeforeEach
    void setUp() {
        // 운영 환경과 동일한 만료시간으로 실제 JWT 생성 객체를 준비한다.
        jwtProvider = new JwtProvider(
                SECRET,
                ACCESS_EXPIRATION,
                REFRESH_EXPIRATION
        );
    }

    @Test
    @DisplayName("Access Token을 발급하면 회원 ID와 ACCESS 유형이 포함된다.")
    void createAccessToken_validUserId_Success() {
        // given
        Long userId = 1L;

        // when
        String token = jwtProvider.createAccessToken(userId);
        Claims claims = jwtProvider.parseToken(token);

        // then
        assertThat(token).isNotBlank();
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("tokenType", String.class)).isEqualTo("ACCESS");
    }

    @Test
    @DisplayName("Refresh Token을 발급하면 회원 ID와 REFRESH 유형이 포함된다.")
    void createRefreshToken_validUserId_Success() {
        // given
        Long userId = 1L;

        // when
        String token = jwtProvider.createRefreshToken(userId);
        Claims claims = jwtProvider.parseToken(token);

        // then
        assertThat(token).isNotBlank();
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("tokenType", String.class)).isEqualTo("REFRESH");
    }

    @Test
    @DisplayName("Access Token의 만료시간은 600초이다.")
    void createAccessToken_validUserId_hasCorrectExpiration() {
        // given
        Long userId = 1L;

        // when
        String token = jwtProvider.createAccessToken(userId);
        Claims claims = jwtProvider.parseToken(token);

        // then
        long expirationSeconds = (
                claims.getExpiration().getTime()
                        - claims.getIssuedAt().getTime()
        ) / 1000;

        assertThat(expirationSeconds).isEqualTo(600L);
        assertThat(jwtProvider.getAccessTokenExpirationSeconds())
                .isEqualTo(600L);
    }

    @Test
    @DisplayName("다른 비밀 키로 서명된 JWT는 검증할 수 없다.")
    void parseToken_differentSecretKey_Fail() {
        // given
        String token = jwtProvider.createAccessToken(1L);

        String anotherSecret = Base64.getEncoder()
                .encodeToString(
                        "another-jwt-provider-test-secret-key-over-32-bytes"
                                .getBytes(StandardCharsets.UTF_8)
                );

        JwtProvider anotherProvider = new JwtProvider(
                anotherSecret,
                ACCESS_EXPIRATION,
                REFRESH_EXPIRATION
        );

        // when & then
        assertThatThrownBy(() -> anotherProvider.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("만료된 Access Token은 검증할 수 없다.")
    void parseToken_expiredAccessToken_Fail() {
        // given
        // 음수 만료시간을 사용하여 이미 만료된 JWT를 생성한다.
        JwtProvider expiredProvider = new JwtProvider(
                SECRET,
                -60_000L,
                REFRESH_EXPIRATION
        );

        String expiredToken = expiredProvider.createAccessToken(1L);

        // when & then
        assertThatThrownBy(() -> jwtProvider.parseToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("Refresh Token의 만료시간을 설정값에 따라 초 단위로 반환한다.")
    void getRefreshTokenExpirationSeconds_customExpiration_Success() {
        // given
        // 기본값인 7일이 아닌 3일로 설정한다.
        long threeDaysInMillis = 3L * 24 * 60 * 60 * 1000;

        JwtProvider customProvider = new JwtProvider(
                SECRET,
                ACCESS_EXPIRATION,
                threeDaysInMillis
        );

        // when
        long expirationSeconds =
                customProvider.getRefreshTokenExpirationSeconds();

        // then
        assertThat(expirationSeconds).isEqualTo(259200L);
    }

    @Test
    @DisplayName("만료된 Refresh Token은 검증할 수 없다.")
    void parseToken_expiredRefreshToken_throwsExpiredJwtException() {
        // given
        // 대기 없이 만료를 검증하도록 이미 만료된 토큰을 생성한다.
        JwtProvider expiredProvider = new JwtProvider(
                SECRET,
                ACCESS_EXPIRATION,
                -60_000L
        );

        String expiredToken = expiredProvider.createRefreshToken(1L);

        // when & then
        assertThatThrownBy(() -> jwtProvider.parseToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("다른 비밀 키로 서명한 Refresh Token은 검증할 수 없다.")
    void parseToken_refreshTokenSignedWithDifferentKey_throwsSignatureException() {
        // given
        String anotherSecret = Base64.getEncoder()
                .encodeToString(
                        "another-jwt-provider-test-secret-key-over-32-bytes"
                                .getBytes(StandardCharsets.UTF_8)
                );

        JwtProvider anotherProvider = new JwtProvider(
                anotherSecret,
                ACCESS_EXPIRATION,
                REFRESH_EXPIRATION
        );

        String refreshToken = anotherProvider.createRefreshToken(1L);

        // when & then
        assertThatThrownBy(() -> jwtProvider.parseToken(refreshToken))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }
}
