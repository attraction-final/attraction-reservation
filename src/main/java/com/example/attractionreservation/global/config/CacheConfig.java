package com.example.attractionreservation.global.config;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

@Configuration
@EnableCaching // @Cacheable, @CacheEvict 를 켜는 스위치
public class CacheConfig implements CachingConfigurer {

    // 캐시 이름은 오타가 나지 않도록 상수로 모아 둔다
    public static final String ATTRACTION_DETAIL = "attractionDetail";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))   // 혹시 지우기를 놓쳐도 10분 뒤엔 새 값으로
                .disableCachingNullValues();        // null 은 저장하지 않는다

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .transactionAware()                 // DB 커밋이 "성공한 뒤에" 캐시를 지운다
                .build();
    }

    // Redis가 잠깐 죽어도 상세 조회는 에러 대신 DB에서 읽어 오도록, 캐시 오류는 로그만 남긴다
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
