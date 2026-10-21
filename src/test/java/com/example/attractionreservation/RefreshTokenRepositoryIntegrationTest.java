package com.example.attractionreservation;

import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class RefreshTokenRepositoryIntegrationTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    private Long userId;
    private String key;

    @BeforeEach
    void setUp() {
        // 다른 테스트의 Redis 키와 겹치지 않도록 테스트 전용 ID를 사용한다.
        userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        key = "auth:refresh:" + userId;
    }

    @AfterEach
    void tearDown() {
        // Redis는 DB 트랜잭션으로 롤백되지 않으므로 사용한 키만 정리한다.
        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("Refresh Token을 SHA-256 해시로 저장하고 설정된 TTL을 적용한다.")
    void save_validRefreshToken_storesHashWithConfiguredTtl() throws Exception {
        // given
        String refreshToken = "refresh-token-to-save";

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(refreshToken.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(refreshTokenExpiration).isPositive();
        long beforeSave = System.nanoTime();

        // when
        refreshTokenRepository.save(userId, refreshToken);

        // then
        String savedHash = redisTemplate.opsForValue().get(key);
        Long remainingTtl =
                redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - beforeSave
        );

        assertThat(savedHash).isEqualTo(expectedHash);
        assertThat(savedHash).isNotEqualTo(refreshToken);
        assertThat(remainingTtl).isNotNull().isPositive();

        // 실제 경과 시간을 반영하고 밀리초 단위 반올림 오차를 허용한다.
        assertThat(remainingTtl).isBetween(
                Math.max(1L, refreshTokenExpiration - elapsedMillis - 100L),
                refreshTokenExpiration
        );
    }

    @Test
    @DisplayName("저장한 Refresh Token과 같은 토큰이면 true를 반환한다.")
    void matches_sameRefreshToken_returnsTrue() {
        // given
        String refreshToken = "saved-refresh-token";
        refreshTokenRepository.save(userId, refreshToken);

        // when
        boolean result = refreshTokenRepository.matches(userId, refreshToken);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("저장한 Refresh Token과 다른 토큰이면 false를 반환한다.")
    void matches_differentRefreshToken_returnsFalse() {
        // given
        refreshTokenRepository.save(userId, "saved-refresh-token");

        // when
        boolean result =
                refreshTokenRepository.matches(userId, "different-refresh-token");

        // then
        assertThat(result).isFalse();
        assertThat(refreshTokenRepository.matches(userId, "saved-refresh-token"))
                .isTrue();
    }

    @Test
    @DisplayName("저장된 Refresh Token이 없으면 false를 반환한다.")
    void matches_missingRefreshToken_returnsFalse() {
        // given
        assertThat(redisTemplate.hasKey(key)).isFalse();

        // when
        boolean result =
                refreshTokenRepository.matches(userId, "missing-refresh-token");

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Refresh Token을 삭제하면 Redis 키가 제거된다.")
    void delete_savedRefreshToken_removesRedisKey() {
        // given
        refreshTokenRepository.save(userId, "saved-refresh-token");
        assertThat(redisTemplate.hasKey(key)).isTrue();

        // when
        refreshTokenRepository.delete(userId);

        // then
        assertThat(redisTemplate.hasKey(key)).isFalse();
        assertThat(refreshTokenRepository.matches(userId, "saved-refresh-token"))
                .isFalse();
    }
}