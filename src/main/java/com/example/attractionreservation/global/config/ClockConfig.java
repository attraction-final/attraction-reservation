package com.example.attractionreservation.global.config;

import com.example.attractionreservation.global.util.KstTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.system(KstTime.ZONE);
    }
}
