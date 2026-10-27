
package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.domain.auth.dto.CreateSocialLoginExchangeRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginResponse;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginServiceResponse;
import com.example.attractionreservation.domain.auth.dto.CreateAuthRefreshResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.service.AuthService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtProvider jwtProvider;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<CreateAuthLoginResponse>> login(
            @Valid @RequestBody CreateAuthLoginRequest request
    ) {
        // 이메일·비밀번호 검증 후 JWT 발급 및 Refresh Token Redis 저장
        CreateAuthLoginServiceResponse result = authService.login(request);

        // Refresh Token은 JavaScript에서 접근할 수 없도록 HttpOnly Cookie로 전달
        ResponseCookie refreshTokenCookie = ResponseCookie.from("refreshToken", result.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofSeconds(jwtProvider.getRefreshTokenExpirationSeconds()))
                .build();

        // Access Token만 응답 Body에 포함하고, Refresh Token은 Cookie로 전달
        return ResponseEntity
                .status(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
                .body(ApiResponse.ok("로그인에 성공했습니다.", result.response()));
    }


    @PostMapping("/oauth2/exchange")
    public ResponseEntity<ApiResponse<CreateAuthLoginResponse>>
    exchangeSocialLoginCode(
            @Valid @RequestBody CreateSocialLoginExchangeRequest request
    ) {
        CreateAuthLoginServiceResponse result =
                authService.exchangeSocialLoginCode(request.code());

        // 일반 로그인과 동일한 Refresh Token 쿠키 정책을 적용한다.
        ResponseCookie refreshTokenCookie = ResponseCookie
                .from("refreshToken", result.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ofSeconds(
                        jwtProvider.getRefreshTokenExpirationSeconds()
                ))
                .build();

        // 일회용 코드와 Refresh Token은 응답 본문에 포함하지 않는다.
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshTokenCookie.toString()
                )
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(
                        "소셜 로그인에 성공했습니다.",
                        result.response()
                ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<CreateAuthRefreshResponse>> refresh(
            @CookieValue(value = "refreshToken", required = false)
            String refreshToken
    ) {
        CreateAuthRefreshResponse response = authService.refresh(refreshToken);

        return ResponseEntity.ok(
                ApiResponse.ok("토큰 재발급에 성공했습니다.", response)
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal Long userId,
            @CookieValue(value = "refreshToken", required = false) String refreshToken
    ) {
        // 인증된 회원의 Refresh Token을 Redis에서 무효화한다.
        authService.logout(userId, refreshToken);

        // 브라우저에 저장된 Refresh Token Cookie를 삭제한다.
        ResponseCookie expiredCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(ApiResponse.ok("로그아웃에 성공했습니다.", null));
    }
}
