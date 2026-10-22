
package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginResponse;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginServiceResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
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

    @Transactional(readOnly = true)
    public CreateAuthLoginServiceResponse login(CreateAuthLoginRequest request) {

        // 이메일, 비밀번호, 회원 상태를 검증한다.
        User user = authenticateUser(request);

        // API 인증에 사용할 Access Token과 재발급에 사용할 Refresh Token을 생성한다.
        String accessToken = jwtProvider.createAccessToken(user.getId());
        String refreshToken = jwtProvider.createRefreshToken(user.getId());

        // 로그아웃 시 무효화할 수 있도록 Refresh Token 해시값을 Redis에 저장한다.
        refreshTokenRepository.save(user.getId(), refreshToken);

        CreateAuthLoginResponse response = CreateAuthLoginResponse.of(
                accessToken,
                jwtProvider.getAccessTokenExpirationSeconds()
        );

        return CreateAuthLoginServiceResponse.of(response, refreshToken);
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

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_CREDENTIALS);
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE);
        }

        return user;
    }
}
