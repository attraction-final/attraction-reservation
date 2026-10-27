package com.example.attractionreservation.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Repository
@RequiredArgsConstructor
public class SocialLoginCodeRepository {

    private static final String KEY_PREFIX = "auth:social-code:";
    private static final Duration EXPIRATION = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    public String issue(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("유효한 회원 ID가 필요합니다.");
        }

        // 예측하기 어려운 32바이트 난수를 URL에 사용할 수 있는 코드로 변환한다.
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);

        String code = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        // Redis에는 원본 코드 대신 해시를 키로 저장하고, 60초 후 만료시킨다.
        Boolean saved = redisTemplate.opsForValue().setIfAbsent(
                createKey(code),
                userId.toString(),
                EXPIRATION
        );

        // 기존 코드가 있으면 덮어쓰지 않는다. 저장 실패 시 코드도 전달하지 않는다.
        if (!Boolean.TRUE.equals(saved)) {
            throw new IllegalStateException(
                    "일회용 로그인 코드를 저장하지 못했습니다."
            );
        }

        return code;
    }

    private String createKey(String code) {
        return KEY_PREFIX + hash(code);
    }

    private String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    code.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 알고리즘을 사용할 수 없습니다.",
                    exception
            );
        }
    }
}