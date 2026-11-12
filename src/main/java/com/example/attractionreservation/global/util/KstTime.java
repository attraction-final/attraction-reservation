package com.example.attractionreservation.global.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

// 모든 시각은 KST 기준
public final class KstTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KstTime() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZONE);
    }
}
