package com.example.attractionreservation;

import com.example.attractionreservation.domain.auth.repository.SocialLoginCodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class SocialLoginCodeRepositoryIntegrationTest {

    @Autowired
    private SocialLoginCodeRepository repository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String key;

    @AfterEach
    void tearDown() {
        // 이 테스트가 발급한 코드의 키만 정리한다.
        if (key != null) {
            redisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("실제 Redis에서 같은 코드는 한 번만 소비할 수 있다")
    void consume_sameCode_succeedsOnlyOnce() throws Exception {
        String code = issueCode();

        assertThat(repository.consume(code)).contains(1L);
        assertThat(repository.consume(code)).isEmpty();
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    @Test
    @DisplayName("동시에 같은 코드를 소비해도 한 요청만 회원 ID를 받는다")
    void consume_concurrently_onlyOneSucceeds() throws Exception {
        String code = issueCode();
        int requestCount = 8;

        var executor = Executors.newFixedThreadPool(requestCount);
        var ready = new CountDownLatch(requestCount);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Optional<Long>>>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 시작 대기 시간 초과");
                    }

                    return repository.consume(code);
                }));
            }

            // 모든 요청을 준비시킨 뒤 같은 코드로 소비를 시작한다.
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successCount = 0;

            for (Future<Optional<Long>> future : futures) {
                Optional<Long> result = future.get(10, TimeUnit.SECONDS);

                if (result.isPresent()) {
                    assertThat(result).contains(1L);
                    successCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(repository.consume(code)).isEmpty();
            assertThat(redisTemplate.hasKey(key)).isFalse();
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private String issueCode() throws Exception {
        String code = repository.issue(1L);

        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(code.getBytes(StandardCharsets.UTF_8))
        );

        key = "auth:social-code:" + hash;
        return code;
    }
}