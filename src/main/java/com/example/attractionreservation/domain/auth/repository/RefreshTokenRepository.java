
package com.example.attractionreservation.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;

    public void save(Long userId, String refreshToken) {
        String key = createKey(userId);
        String tokenHash = hashToken(refreshToken);

        redisTemplate.opsForValue().set(
                key,
                tokenHash,
                Duration.ofMillis(refreshTokenExpiration)
        );
    }

    public boolean matches(Long userId, String refreshToken) {
        String savedTokenHash = redisTemplate.opsForValue()
                .get(createKey(userId));

        if (savedTokenHash == null) {
            return false;
        }

        byte[] savedHash = HexFormat.of().parseHex(savedTokenHash);
        byte[] providedHash = HexFormat.of().parseHex(hashToken(refreshToken));

        return MessageDigest.isEqual(savedHash, providedHash);
    }

    public void delete(Long userId) {
        redisTemplate.delete(createKey(userId));
    }

    private String createKey(Long userId) {
        return KEY_PREFIX + userId;
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
