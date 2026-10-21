package com.example.attractionreservation;

import com.example.attractionreservation.domain.auth.dto.CreateAuthRefreshResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.auth.service.AuthService;
import com.example.attractionreservation.domain.user.dto.CreateUserRequest;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.service.UserService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@Transactional
class AuthRefreshIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("실제 토큰 재발급 시 600초 Access Token을 반환하고 Redis 해시와 만료 시각을 유지한다.")
    void refresh_validRefreshToken_preservesRedisHashAndExpiration() throws Exception {
        // given
        CreateUserResponse user = userService.signup(
                new CreateUserRequest(
                        UUID.randomUUID() + "@example.com",
                        "Password123!",
                        "재발급테스트"
                )
        );

        Long userId = user.id();
        String refreshToken = jwtProvider.createRefreshToken(userId);
        String key = "auth:refresh:" + userId;

        try {
            refreshTokenRepository.save(userId, refreshToken);

            // 저장을 다시 수행하면 TTL이 초기화되는 오류를 확실히 감지하도록
            // 테스트용 토큰의 Redis 잔여 유효기간을 짧게 설정한다.
            assertThat(redisTemplate.expire(key, Duration.ofMinutes(1)))
                    .isTrue();

            String hashBefore = redisTemplate.opsForValue().get(key);
            Long expirationBefore = getExpirationTimeMillis(key);

            String expectedHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(refreshToken.getBytes(StandardCharsets.UTF_8))
            );

            assertThat(hashBefore).isEqualTo(expectedHash);
            assertThat(expirationBefore).isPositive();

            // when
            CreateAuthRefreshResponse response = authService.refresh(refreshToken);

            // then
            Claims accessClaims = jwtProvider.parseToken(response.accessToken());

            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresIn()).isEqualTo(600L);
            assertThat(accessClaims.getSubject()).isEqualTo(userId.toString());
            assertThat(accessClaims.get("tokenType", String.class))
                    .isEqualTo("ACCESS");
            assertThat(
                    accessClaims.getExpiration().getTime()
                            - accessClaims.getIssuedAt().getTime()
            ).isEqualTo(600_000L);

            assertThat(redisTemplate.opsForValue().get(key))
                    .isEqualTo(hashBefore);
            assertThat(getExpirationTimeMillis(key))
                    .isEqualTo(expirationBefore);
            assertThat(refreshTokenRepository.matches(userId, refreshToken))
                    .isTrue();
        } finally {
            // Redis 변경은 DB 트랜잭션으로 롤백되지 않아 직접 정리한다.
            refreshTokenRepository.delete(userId);
        }
    }

    private Long getExpirationTimeMillis(String key) {
        // 정수 반환 타입을 명시하여 Redis의 절대 만료 시각을 조회한다.
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                "return redis.call('PEXPIRETIME', KEYS[1])",
                Long.class
        );

        return redisTemplate.execute(script, List.of(key));
    }
}