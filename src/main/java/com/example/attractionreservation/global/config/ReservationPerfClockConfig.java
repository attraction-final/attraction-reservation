package com.example.attractionreservation.global.config;

import com.example.attractionreservation.global.util.KstTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.OffsetDateTime;

// 전용 부하 테스트 profile에서만 Clock 기반 업무 시각을 고정한다.
@Configuration
@Profile("reservation-create-perf-time")
public class ReservationPerfClockConfig {

    @Bean
    // profile 활성 시 기존 실제 시각 Clock보다 이 bean을 우선 주입한다.
    @Primary
    public Clock reservationPerfClock(
            @Value("${reservation.perf.fixed-time}") String fixedTime
    ) {
        // 설정 문자열의 오프셋으로 기준 Instant를 구하고, KST 기준 Clock으로 제공한다.
        // fixed Clock은 시간이 흐르지 않으므로 실제 마감 경계 검증과는 구분해서 사용한다.
        return Clock.fixed(
                OffsetDateTime.parse(fixedTime).toInstant(),
                KstTime.ZONE
        );
    }
}
