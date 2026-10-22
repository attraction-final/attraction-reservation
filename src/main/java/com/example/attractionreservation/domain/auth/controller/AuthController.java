
package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginResponse;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginServiceResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.service.AuthService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
