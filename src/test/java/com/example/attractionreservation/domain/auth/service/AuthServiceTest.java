
package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginServiceResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @DisplayName("올바른 이메일과 비밀번호로 로그인할 수 있다.")
    void login_validCredentials_Success() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        User user = createUser(UserStatus.ACTIVE);

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPassword()))
                .thenReturn(true);
        when(jwtProvider.createAccessToken(1L))
                .thenReturn("access-token");
        when(jwtProvider.createRefreshToken(1L))
                .thenReturn("refresh-token");
        when(jwtProvider.getAccessTokenExpirationSeconds())
                .thenReturn(600L);

        // when
        CreateAuthLoginServiceResponse result = authService.login(request);

        // then
        assertThat(result.response().accessToken()).isEqualTo("access-token");
        assertThat(result.response().tokenType()).isEqualTo("Bearer");
        assertThat(result.response().expiresIn()).isEqualTo(600L);
        assertThat(result.refreshToken()).isEqualTo("refresh-token");

        verify(refreshTokenRepository).save(1L, "refresh-token");
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인할 수 없다.")
    void login_emailNotFound_Fail() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("unknown@example.com", "Password!123");

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_LOGIN_CREDENTIALS.getMessage());

        verifyNoInteractions(jwtProvider, refreshTokenRepository);
    }


    @Test
    @DisplayName("비밀번호가 올바르지 않으면 로그인할 수 없다.")
    void login_invalidPassword_Fail() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "WrongPassword");

        User user = createUser(UserStatus.ACTIVE);

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_LOGIN_CREDENTIALS.getMessage());

        verify(passwordEncoder).matches(
                request.password(),
                user.getPassword()
        );
        verifyNoInteractions(jwtProvider, refreshTokenRepository);
    }


    @Test
    @DisplayName("비활성 회원은 로그인할 수 없다.")
    void login_inactiveUser_Fail() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        User user = createUser(UserStatus.INACTIVE);

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPassword()))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.USER_NOT_ACTIVE.getMessage());

        verifyNoInteractions(jwtProvider, refreshTokenRepository);
    }

    @Test
    @DisplayName("로그인에 성공하면 Refresh Token을 Redis 저장소에 전달한다.")
    void login_validCredentials_savesRefreshToken() {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        User user = createUser(UserStatus.ACTIVE);

        when(userRepository.findByEmail(request.email()))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPassword()))
                .thenReturn(true);
        when(jwtProvider.createAccessToken(1L))
                .thenReturn("access-token");
        when(jwtProvider.createRefreshToken(1L))
                .thenReturn("refresh-token");

        // when
        authService.login(request);

        // then
        verify(refreshTokenRepository, times(1))
                .save(1L, "refresh-token");
    }

    @Test
    @DisplayName("유효한 Refresh Token이면 로그아웃에 성공하고 Redis에서 토큰을 삭제한다.")
    void logout_validRefreshToken_deletesToken() {
        // given
        Long userId = 1L;
        String refreshToken = "valid-refresh-token";

        when(refreshTokenRepository.deleteIfMatches(userId, refreshToken))
                .thenReturn(true);

        // when
        authService.logout(userId, refreshToken);

        // then
        verify(refreshTokenRepository).deleteIfMatches(userId, refreshToken);
    }

    @Test
    @DisplayName("Refresh Token이 일치하지 않으면 로그아웃에 실패하고 토큰을 삭제하지 않는다.")
    void logout_invalidRefreshToken_throwsBusinessException() {
        // given
        Long userId = 1L;
        String refreshToken = "invalid-refresh-token";

        when(refreshTokenRepository.deleteIfMatches(userId, refreshToken))
                .thenReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.logout(userId, refreshToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_REFRESH_TOKEN.getMessage());

        verify(refreshTokenRepository).deleteIfMatches(userId, refreshToken);
    }

    @Test
    @DisplayName("Refresh Token이 없으면 로그아웃에 실패하고 Redis에 접근하지 않는다.")
    void logout_missingRefreshToken_throwsBusinessException() {
        // given
        Long userId = 1L;
        String refreshToken = null;

        // when & then
        assertThatThrownBy(() -> authService.logout(userId, refreshToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_REFRESH_TOKEN.getMessage());

        verifyNoInteractions(refreshTokenRepository);
    }


    private User createUser(UserStatus status) {
        // DB를 사용하지 않고 테스트용 회원 객체를 생성한다.
        User user = mock(User.class);

        when(user.getPassword()).thenReturn("encoded-password");

        // 비밀번호 검증에 실패하는 테스트에서는 회원 상태를 조회하지 않는다.
        lenient().when(user.getStatus()).thenReturn(status);

        // 로그인 성공 시 토큰 발급에 사용할 회원 ID를 설정한다.
        if (status == UserStatus.ACTIVE) {
            lenient().when(user.getId()).thenReturn(1L);
        }

        return user;
    }

}
