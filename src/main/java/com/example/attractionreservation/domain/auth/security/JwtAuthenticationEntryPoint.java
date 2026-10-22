
package com.example.attractionreservation.domain.auth.security;

import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {

        // 인증 정보가 없는 요청은 401 Unauthorized로 처리한다.
        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;

        // 팀 공통 API 응답 형식에 맞춰 HTTP 상태와 JSON 응답을 설정한다.
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.error(errorCode)
        );
    }
}
