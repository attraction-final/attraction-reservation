package com.example.attractionreservation.global.config;

import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPerfClockConfigTest {

    private static final String PROFILE = "reservation-create-perf-time";
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class, ReservationPerfClockConfig.class);

    @Test
    @DisplayName("전용 profile이 없으면 고정 시각 설정이 있어도 실제 KST Clock을 사용한다")
    void useSystemClockWithoutDedicatedProfile() {
        // given
        var runner = contextRunner.withPropertyValues("reservation.perf.fixed-time=invalid");

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed().hasSingleBean(Clock.class);
            assertThat(context).doesNotHaveBean("reservationPerfClock");
            Clock clock = context.getBean(Clock.class);
            assertThat(clock.getZone()).isEqualTo(KstTime.ZONE);
            Instant before = Instant.now();
            Instant actual = clock.instant();
            Instant after = Instant.now();
            assertThat(actual).isBetween(before, after);
        });
    }

    @Test
    @DisplayName("전용 profile에서는 기본 Clock을 유지하면서 고정 KST Clock을 우선 주입한다")
    void preferFixedClockOnlyWithDedicatedProfile() {
        // given
        var runner = contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(PROFILE))
                .withPropertyValues("reservation.perf.fixed-time=2026-11-10T14:00:00+09:00");

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(Clock.class)).hasSize(2);
            Clock clock = context.getBean(Clock.class);
            assertThat(clock).isSameAs(context.getBean("reservationPerfClock"));
            assertThat(clock).isNotSameAs(context.getBean("clock"));
            assertThat(clock.getZone()).isEqualTo(KstTime.ZONE);
            assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-10T05:00:00Z"));
            assertThat(LocalDateTime.now(clock)).isEqualTo(LocalDateTime.of(2026, 11, 10, 14, 0));
        });
    }

    @Test
    @DisplayName("다른 profile에서는 고정 시각 설정이 있어도 부하용 Clock을 생성하지 않는다")
    void keepPerfClockDisabledWithAnotherProfile() {
        // given
        var runner = contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withPropertyValues("reservation.perf.fixed-time=2026-11-10T14:00:00+09:00");

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasNotFailed().hasSingleBean(Clock.class);
            assertThat(context).doesNotHaveBean("reservationPerfClock");
            assertThat(context.getBean(Clock.class)).isSameAs(context.getBean("clock"));
        });
    }

    @Test
    @DisplayName("전용 profile에 고정 시각 설정이 없으면 시작에 실패한다")
    void failStartupWithoutRequiredFixedTime() {
        // given
        var runner = contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(PROFILE));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "2026-11-10T14:00:00", "2026-02-30T14:00:00+09:00"})
    @DisplayName("고정 시각 형식이나 날짜가 잘못되면 시작에 실패한다")
    void failStartupWithInvalidFixedTime(String fixedTime) {
        // given
        var runner = contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(PROFILE))
                .withPropertyValues("reservation.perf.fixed-time=" + fixedTime);

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
        });
    }
}
