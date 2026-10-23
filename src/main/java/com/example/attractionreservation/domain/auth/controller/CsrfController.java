package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.global.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class CsrfController {

    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<CsrfResponse>> csrf(CsrfToken csrfToken) {
        // 응답 토큰과 함께 검증용 쿠키가 발급되며, 응답은 캐시하지 않는다.
        CsrfResponse response = new CsrfResponse(
                csrfToken.getHeaderName(),
                csrfToken.getToken()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok("CSRF 토큰 조회에 성공했습니다.", response));
    }

    public record CsrfResponse(
            String headerName,
            String token
    ) {
    }
}