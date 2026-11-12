package com.example.attractionreservation.domain.auth.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialLoginCodeRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SocialLoginCodeRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SocialLoginCodeRepository(redisTemplate);
    }

    @Test
    @DisplayName("회원 ID를 60초 만료 조건으로 저장하고 원본 코드는 키에 노출하지 않는다")
    void issue_storesWithExpiration() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                anyString(), eq("1"), eq(Duration.ofSeconds(60))
        )).thenReturn(true);

        String code = repository.issue(1L);

        var keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(
                keyCaptor.capture(),
                eq("1"),
                eq(Duration.ofSeconds(60))
        );

        // 프론트엔드 URL에 전달할 수 있는 형태인지 확인한다.
        assertThat(code).matches("[A-Za-z0-9_-]{43}");

        // Redis 키에는 원본 코드 대신 SHA-256 해시가 저장된다.
        assertThat(keyCaptor.getValue())
                .matches("auth:social-code:[0-9a-f]{64}")
                .doesNotContain(code);
    }

    @Test
    @DisplayName("저장하지 못한 코드는 성공 결과로 반환하지 않는다")
    void issue_storageRejected_throwsException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                anyString(), anyString(), any(Duration.class)
        )).thenReturn(false);

        assertThatThrownBy(() -> repository.issue(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("유효하지 않은 회원 ID는 Redis에 저장하지 않는다")
    void issue_invalidUserId_rejected() {
        assertThatThrownBy(() -> repository.issue(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> repository.issue(0L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("저장된 코드를 소비하면 연결된 회원 ID를 반환한다")
    void consume_existingCode_returnsUserId() {
        String code = "a".repeat(43);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenReturn("1");

        Optional<Long> result = repository.consume(code);

        assertThat(result).contains(1L);

        var keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).getAndDelete(keyCaptor.capture());

        assertThat(keyCaptor.getValue())
                .matches("auth:social-code:[0-9a-f]{64}")
                .doesNotContain(code);
    }

    @Test
    @DisplayName("존재하지 않거나 만료·사용된 코드는 빈 결과를 반환한다")
    void consume_missingCode_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        assertThat(repository.consume("a".repeat(43))).isEmpty();
    }

    @Test
    @DisplayName("형식이 잘못된 코드는 Redis에 접근하지 않는다")
    void consume_invalidFormat_returnsEmpty() {
        assertThat(repository.consume(null)).isEmpty();
        assertThat(repository.consume("")).isEmpty();
        assertThat(repository.consume(" ")).isEmpty();
        assertThat(repository.consume("a".repeat(42))).isEmpty();
        assertThat(repository.consume("a".repeat(44))).isEmpty();
        assertThat(repository.consume("!".repeat(43))).isEmpty();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("저장된 회원 ID가 손상됐으면 서버 오류로 처리한다")
    void consume_corruptedUserId_throwsException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString()))
                .thenReturn("not-a-number");

        assertThatThrownBy(() -> repository.consume("a".repeat(43)))
                .isInstanceOf(IllegalStateException.class);
    }
}