
package com.example.attractionreservation.domain.auth.security;

import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {

        // 인증된 사용자가 필요한 권한을 가지고 있지 않으면 403을 반환한다.
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;

        var authentication =
                SecurityContextHolder.getContext().getAuthentication();

        Long userId = authentication != null
                && authentication.getPrincipal() instanceof Long id
                ? id
                : null;

        log.warn(
                "event=ACCESS_DENIED result=REJECTED "
                        + "userId={} method={} errorCode={} httpStatus={}",
                userId,
                request.getMethod(),
                errorCode.name(),
                errorCode.getStatus().value()
        );

        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // 팀의 공통 API 오류 응답 형식을 유지한다.
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.error(errorCode)
        );
    }
}
