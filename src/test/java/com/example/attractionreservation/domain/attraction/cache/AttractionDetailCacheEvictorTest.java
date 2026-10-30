package com.example.attractionreservation.domain.attraction.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Redis 없이, 가짜 StringRedisTemplate 으로 "언제, 어떤 키를 지우는지"만 검사하는 단위 테스트
@ExtendWith(MockitoExtension.class)
class AttractionDetailCacheEvictorTest {

    private static final String EXPECTED_KEY = "attractionDetail::1";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private AttractionDetailCacheEvictor evictor;

    // 트랜잭션 흉내(동기화)를 켠 테스트가 다음 테스트에 영향을 주지 않도록 매번 꺼 준다
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션 밖에서 부르면 attractionDetail::{id} 키를 바로 지운다")
    void noTransaction_EvictsImmediately() {
        // when
        evictor.evictAfterCommit(1L);

        // then
        verify(stringRedisTemplate).delete(EXPECTED_KEY);
    }

    @Test
    @DisplayName("트랜잭션 안에서 부르면 커밋 전에는 지우지 않고, 커밋 후에 지운다")
    void inTransaction_EvictsOnlyAfterCommit() {
        // given: 스프링이 트랜잭션을 시작할 때 하는 일을 흉내 낸다
        TransactionSynchronizationManager.initSynchronization();

        // when
        evictor.evictAfterCommit(1L);

        // then 1) 아직 커밋 전 → Redis 를 건드리지 않아야 한다
        verifyNoInteractions(stringRedisTemplate);

        // then 2) "커밋 후 할 일" 이 정확히 1개 예약되어 있어야 한다
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        // when 2) 커밋 성공을 흉내 낸다
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        // then 3) 이제서야 지운다
        verify(stringRedisTemplate).delete(EXPECTED_KEY);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 캐시를 지우지 않는다")
    void inTransaction_RolledBack_DoesNotEvict() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        evictor.evictAfterCommit(1L);

        // when: 롤백 때 스프링은 afterCommit 을 부르지 않고 afterCompletion(롤백)만 부른다
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // then
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("Redis 가 죽어 삭제에 실패해도 예외를 밖으로 던지지 않는다")
    void redisFailure_DoesNotThrow() {
        // given
        given(stringRedisTemplate.delete(anyString()))
                .willThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        // when & then: 관리자 수정 API 가 캐시 때문에 실패하면 안 된다
        assertThatCode(() -> evictor.evictAfterCommit(1L))
                .doesNotThrowAnyException();
        verify(stringRedisTemplate).delete(EXPECTED_KEY); // 지우려는 시도는 했는지
    }
}
