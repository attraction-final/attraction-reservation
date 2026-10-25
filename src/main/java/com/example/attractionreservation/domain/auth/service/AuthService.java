
package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.repository.SocialLoginCodeRepository;
import com.example.attractionreservation.domain.user.entity.AuthProvider;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SocialLoginCodeRepository socialLoginCodeRepository;

    @Transactional(readOnly = true)
    public CreateAuthLoginServiceResponse login(CreateAuthLoginRequest request) {
        User user = authenticateUser(request);
        return issueTokens(user.getId());
    }

    @Transactional(readOnly = true)
    public CreateAuthLoginServiceResponse exchangeSocialLoginCode(String code) {
        // 읽기와 삭제를 원자적으로 처리하여 동일 코드는 한 번만 사용한다.
        Long userId = socialLoginCodeRepository.consume(code)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SOCIAL_LOGIN_CODE_INVALID
                ));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SOCIAL_LOGIN_CODE_INVALID
                ));

        // 제공자 인증 이후 정지·탈퇴했을 수 있으므로 상태를 다시 확인한다.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE);
        }

        return issueTokens(user.getId());
    }

    private CreateAuthLoginServiceResponse issueTokens(Long userId) {
        // 일반 로그인과 소셜 코드 교환에 동일한 토큰 발급 정책을 적용한다.
        String accessToken = jwtProvider.createAccessToken(userId);
        String refreshToken = jwtProvider.createRefreshToken(userId);

        // 기존 저장 정책을 재사용하여 로그아웃 시 무효화할 수 있게 한다.
        refreshTokenRepository.save(userId, refreshToken);

        CreateAuthLoginResponse response = CreateAuthLoginResponse.of(
                accessToken,
                jwtProvider.getAccessTokenExpirationSeconds()
        );

        return CreateAuthLoginServiceResponse.of(response, refreshToken);
    }

    @Transactional(readOnly = true)
    public CreateAuthRefreshResponse refresh(String refreshToken) {
        Long userId = validateRefreshToken(refreshToken);

        // Redis에 저장된 토큰 해시와 일치하는지 확인한다.
        if (!refreshTokenRepository.matches(userId, refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE);
        }

        // Refresh Token을 변경하지 않고 Access Token만 발급한다.
        return CreateAuthRefreshResponse.of(
                jwtProvider.createAccessToken(userId),
                jwtProvider.getAccessTokenExpirationSeconds()
        );
    }

    private Long validateRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        try {
            // 서명과 만료 여부를 검증한다.
            Claims claims = jwtProvider.parseToken(refreshToken);

            if (!"REFRESH".equals(claims.get("tokenType", String.class))) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }

            Long userId = Long.valueOf(claims.getSubject());

            if (userId <= 0) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }

            return userId;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    public void logout(Long userId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()
                || !refreshTokenRepository.deleteIfMatches(userId, refreshToken)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }


    private User authenticateUser(CreateAuthLoginRequest request) {

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS));

        // 소셜 회원은 비밀번호가 없으므로 이메일·비밀번호 로그인을 허용하지 않는다.
        if (user.getProvider() != AuthProvider.LOCAL
                || user.getPassword() == null
                || !passwordEncoder.matches(
                request.password(),
                user.getPassword()
        )) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE);
        }

        return user;
    }
}
