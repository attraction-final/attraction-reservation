package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.user.entity.AuthProvider;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginServiceResponse;
import com.example.attractionreservation.domain.auth.dto.CreateAuthRefreshResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("인증 정보가 유효하고 ACTIVE 회원이면 토큰을 발급하고 Refresh Token을 저장한다.")
    void login_validCredentialsAndActiveUser_returnsTokensAndSavesRefreshToken() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        User user = mock(User.class);
        when(user.getProvider()).thenReturn(AuthProvider.LOCAL);
        when(user.getPassword()).thenReturn("encoded-password");
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getId()).thenReturn(1L);

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), "encoded-password"))
                .thenReturn(true);
        when(jwtProvider.createAccessToken(1L)).thenReturn("access-token");
        when(jwtProvider.createRefreshToken(1L)).thenReturn("refresh-token");
        when(jwtProvider.getAccessTokenExpirationSeconds()).thenReturn(600L);

        // when
        CreateAuthLoginServiceResponse result = authService.login(request);

        // then
        assertThat(result.response().accessToken()).isEqualTo("access-token");
        assertThat(result.response().tokenType()).isEqualTo("Bearer");
        assertThat(result.response().expiresIn()).isEqualTo(600L);
        assertThat(result.refreshToken()).isEqualTo("refresh-token");

        verify(refreshTokenRepository).save(1L, "refresh-token");
        verifyNoMoreInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("이메일에 해당하는 회원이 없으면 로그인 인증 오류를 반환한다.")
    void login_emailNotFound_throwsInvalidLoginCredentials() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("unknown@example.com", "Password!123");

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_LOGIN_CREDENTIALS)
                );

        verifyNoInteractions(
                passwordEncoder,
                jwtProvider,
                refreshTokenRepository
        );
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 로그인 인증 오류를 반환하고 토큰을 발급하지 않는다.")
    void login_passwordMismatch_throwsInvalidLoginCredentials() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "WrongPassword");

        User user = mock(User.class);
        when(user.getProvider()).thenReturn(AuthProvider.LOCAL);
        when(user.getPassword()).thenReturn("encoded-password");

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), "encoded-password"))
                .thenReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_LOGIN_CREDENTIALS)
                );

        verify(passwordEncoder)
                .matches(request.password(), "encoded-password");
        verifyNoInteractions(jwtProvider, refreshTokenRepository);
    }

    @Test
    @DisplayName("비밀번호가 일치해도 INACTIVE 회원이면 로그인을 거부한다.")
    void login_inactiveUser_throwsUserNotActive() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        User user = mock(User.class);
        when(user.getProvider()).thenReturn(AuthProvider.LOCAL);
        when(user.getPassword()).thenReturn("encoded-password");
        when(user.getStatus()).thenReturn(UserStatus.INACTIVE);

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), "encoded-password"))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_ACTIVE)
                );

        verifyNoInteractions(jwtProvider, refreshTokenRepository);
    }

    @Test
    @DisplayName("유효한 Refresh Token과 ACTIVE 회원이면 기존 Refresh Token을 변경하지 않고 Access Token을 반환한다.")
    void refresh_validRefreshTokenAndActiveUser_returnsAccessTokenWithoutUpdatingRefreshToken() {
        // given
        String refreshToken = "valid-refresh-token";

        Claims claims = Jwts.claims()
                .subject("1")
                .add("tokenType", "REFRESH")
                .build();

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);

        when(jwtProvider.parseToken(refreshToken)).thenReturn(claims);
        when(refreshTokenRepository.matches(1L, refreshToken))
                .thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtProvider.createAccessToken(1L)).thenReturn("new-access-token");
        when(jwtProvider.getAccessTokenExpirationSeconds()).thenReturn(600L);

        // when
        CreateAuthRefreshResponse result = authService.refresh(refreshToken);

        // then
        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isEqualTo(600L);

        verify(jwtProvider, never()).createRefreshToken(anyLong());
        verify(refreshTokenRepository).matches(1L, refreshToken);
        verifyNoMoreInteractions(refreshTokenRepository);
        verifyNoInteractions(passwordEncoder);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("Refresh Token이 누락되거나 공백이면 JWT 검증과 저장소 접근 전에 재발급을 거부한다.")
    void refresh_missingOrBlankRefreshToken_throwsInvalidRefreshToken(
            String refreshToken
    ) {
        // given: 누락·빈 문자열·공백 토큰을 각각 전달한다.

        // when & then
        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verifyNoInteractions(
                jwtProvider,
                refreshTokenRepository,
                userRepository,
                passwordEncoder
        );
    }

    @Test
    @DisplayName("Redis에서 Refresh Token이 확인되지 않으면 회원 조회와 토큰 발급 전에 거부한다.")
    void refresh_refreshTokenNotMatchedInRedis_throwsInvalidRefreshToken() {
        // given
        String refreshToken = "unmatched-refresh-token";

        Claims claims = Jwts.claims()
                .subject("1")
                .add("tokenType", "REFRESH")
                .build();

        when(jwtProvider.parseToken(refreshToken)).thenReturn(claims);
        when(refreshTokenRepository.matches(1L, refreshToken))
                .thenReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verify(jwtProvider).parseToken(refreshToken);
        verifyNoMoreInteractions(jwtProvider);

        verify(refreshTokenRepository).matches(1L, refreshToken);
        verifyNoMoreInteractions(refreshTokenRepository);

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @ParameterizedTest
    @EnumSource(
            value = UserStatus.class,
            names = "ACTIVE",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("Refresh Token이 유효해도 ACTIVE가 아닌 회원이면 재발급을 거부한다.")
    void refresh_nonActiveUser_throwsUserNotActive(UserStatus status) {
        // given
        String refreshToken = "valid-refresh-token";

        Claims claims = Jwts.claims()
                .subject("1")
                .add("tokenType", "REFRESH")
                .build();

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(status);

        when(jwtProvider.parseToken(refreshToken)).thenReturn(claims);
        when(refreshTokenRepository.matches(1L, refreshToken))
                .thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_ACTIVE)
                );

        verify(jwtProvider).parseToken(refreshToken);
        verifyNoMoreInteractions(jwtProvider);

        verify(refreshTokenRepository).matches(1L, refreshToken);
        verifyNoMoreInteractions(refreshTokenRepository);

        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("Refresh Token의 JWT 검증에 실패하면 저장소 접근과 토큰 발급 전에 거부한다.")
    void refresh_jwtValidationFailed_throwsInvalidRefreshToken() {
        // given
        String refreshToken = "invalid-refresh-token";

        when(jwtProvider.parseToken(refreshToken))
                .thenThrow(new JwtException("JWT validation failed"));

        // when & then
        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verify(jwtProvider).parseToken(refreshToken);
        verifyNoMoreInteractions(jwtProvider);

        verifyNoInteractions(
                refreshTokenRepository,
                userRepository,
                passwordEncoder
        );
    }

    @Test
    @DisplayName("Access Token을 재발급용 토큰으로 전달하면 저장소 접근 전에 거부한다.")
    void refresh_accessTokenProvided_throwsInvalidRefreshToken() {
        // given
        String accessToken = "valid-access-token";

        Claims claims = Jwts.claims()
                .subject("1")
                .add("tokenType", "ACCESS")
                .build();

        when(jwtProvider.parseToken(accessToken)).thenReturn(claims);

        // when & then
        assertThatThrownBy(() -> authService.refresh(accessToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verify(jwtProvider).parseToken(accessToken);
        verifyNoMoreInteractions(jwtProvider);

        verifyNoInteractions(
                refreshTokenRepository,
                userRepository,
                passwordEncoder
        );
    }

    @Test
    @DisplayName("Refresh Token이 유효해도 해당 회원이 없으면 토큰을 발급하지 않는다.")
    void refresh_userNotFound_throwsInvalidRefreshToken() {
        // given
        String refreshToken = "valid-refresh-token";

        Claims claims = Jwts.claims()
                .subject("1")
                .add("tokenType", "REFRESH")
                .build();

        when(jwtProvider.parseToken(refreshToken)).thenReturn(claims);
        when(refreshTokenRepository.matches(1L, refreshToken))
                .thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verify(jwtProvider).parseToken(refreshToken);
        verifyNoMoreInteractions(jwtProvider);

        verify(refreshTokenRepository).matches(1L, refreshToken);
        verifyNoMoreInteractions(refreshTokenRepository);

        verify(userRepository).findById(1L);
        verifyNoInteractions(passwordEncoder);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", " ", "0", "-1", "9223372036854775808"})
    @DisplayName("Refresh Token의 회원 ID가 유효하지 않으면 저장소 접근 전에 거부한다.")
    void refresh_invalidUserId_throwsInvalidRefreshToken(String subject) {
        // given
        String refreshToken = "invalid-subject-refresh-token";

        Claims claims = Jwts.claims()
                .subject(subject)
                .add("tokenType", "REFRESH")
                .build();

        when(jwtProvider.parseToken(refreshToken)).thenReturn(claims);

        // when & then
        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verify(jwtProvider).parseToken(refreshToken);
        verifyNoMoreInteractions(jwtProvider);

        verifyNoInteractions(
                refreshTokenRepository,
                userRepository,
                passwordEncoder
        );
    }

    @Test
    @DisplayName("저장소에서 Refresh Token의 일치 및 삭제가 성공하면 로그아웃에 성공한다.")
    void logout_matchingRefreshToken_completesLogout() {
        // given
        Long userId = 1L;
        String refreshToken = "valid-refresh-token";

        when(refreshTokenRepository.deleteIfMatches(userId, refreshToken))
                .thenReturn(true);

        // when
        authService.logout(userId, refreshToken);

        // then
        verify(refreshTokenRepository)
                .deleteIfMatches(userId, refreshToken);
        verifyNoMoreInteractions(refreshTokenRepository);
        verifyNoInteractions(jwtProvider, userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("저장소에서 Refresh Token의 일치 및 삭제가 실패하면 로그아웃을 거부한다.")
    void logout_unmatchedRefreshToken_throwsInvalidRefreshToken() {
        // given
        Long userId = 1L;
        String refreshToken = "invalid-refresh-token";

        when(refreshTokenRepository.deleteIfMatches(userId, refreshToken))
                .thenReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.logout(userId, refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verify(refreshTokenRepository)
                .deleteIfMatches(userId, refreshToken);
        verifyNoMoreInteractions(refreshTokenRepository);
        verifyNoInteractions(jwtProvider, userRepository, passwordEncoder);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("Refresh Token이 누락되거나 공백이면 저장소 접근 전에 로그아웃을 거부한다.")
    void logout_missingOrBlankRefreshToken_throwsInvalidRefreshToken(
            String refreshToken
    ) {
        // given
        Long userId = 1L;

        // when & then
        assertThatThrownBy(() -> authService.logout(userId, refreshToken))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
                );

        verifyNoInteractions(
                refreshTokenRepository,
                jwtProvider,
                userRepository,
                passwordEncoder
        );
    }

    @ParameterizedTest
    @EnumSource(
            value = AuthProvider.class,
            names = {"KAKAO", "GOOGLE"}
    )
    @DisplayName("소셜 회원은 이메일·비밀번호 로그인으로 토큰을 발급받을 수 없다")
    void login_socialMember_rejected(AuthProvider provider) {
        var request = new CreateAuthLoginRequest(
                "social@example.com",
                "Password!123"
        );

        User user = User.createSocial(
                "social@example.com",
                "소셜회원",
                provider,
                "provider-123"
        );

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_LOGIN_CREDENTIALS)
                );

        // 비밀번호 비교와 서비스 토큰 발급 모두 진행하지 않는다.
        verifyNoInteractions(
                passwordEncoder,
                jwtProvider,
                refreshTokenRepository
        );
    }
}