
package com.example.attractionreservation.domain.auth.jwt;

import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCESS_TOKEN_TYPE = "ACCESS";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

        // JWT가 없는 요청은 인증 정보를 만들지 않고 다음 필터로 전달한다.
        // 보호된 API라면 Spring Security가 이후 401 응답을 처리한다.
        if (authorizationHeader == null
                || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String accessToken = authorizationHeader.substring(BEARER_PREFIX.length());

        try {
            // JWT 서명과 만료 시간을 검증한다.
            Claims claims = jwtProvider.parseToken(accessToken);

            // Refresh Token을 일반 API 인증에 사용할 수 없도록 차단한다.
            if (!ACCESS_TOKEN_TYPE.equals(claims.get("tokenType", String.class))) {
                throw new BadCredentialsException("올바르지 않은 토큰 유형입니다.");
            }

            Long userId = Long.valueOf(claims.getSubject());

            // 실제 회원의 존재 여부와 현재 상태를 확인한다.
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BadCredentialsException("존재하지 않는 회원입니다."));

            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new BadCredentialsException("이용할 수 없는 회원입니다.");
            }

            // JWT의 사용자 ID를 인증 주체로 사용하고 실제 회원 권한을 등록한다.
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            user.getId(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                    );

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (JwtException | IllegalArgumentException | BadCredentialsException exception) {
            // 인증 실패 시 공통 오류 처리기에 위임하여 JSON 형식의 401을 반환한다.
            SecurityContextHolder.clearContext();

            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("유효하지 않은 인증 정보입니다.", exception)
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
