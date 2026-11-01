package com.example.attractionreservation.domain.attraction.cache;

import com.example.attractionreservation.global.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 어트랙션 상세 캐시를 "DB 커밋 직후, 확실하게" 지우는 도우미.
 * @CacheEvict 의 지우기가 즉시 반영되지 않는 현상이 확인되어 직접 삭제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttractionDetailCacheEvictor {

    // RedisCache 기본 키 모양: "캐시이름::키"  →  "attractionDetail::1"
    private static final String KEY_PREFIX = CacheConfig.ATTRACTION_DETAIL + "::";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 트랜잭션 안에서 부르면 → 커밋이 끝난 뒤에 지운다.
     * 트랜잭션 밖에서 부르면 → 바로 지운다.
     */
    public void evictAfterCommit(Long attractionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow(attractionId);
                }
            });
            return;
        }
        evictNow(attractionId);
    }

    private void evictNow(Long attractionId) {
        String key = KEY_PREFIX + attractionId;
        try {
            Boolean deleted = stringRedisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("어트랙션 상세 캐시 삭제 실패: key={}", key, e);
        }
    }
}