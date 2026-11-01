package com.example.attractionreservation.global.util;

// logfmt 로그의 따옴표 안에 넣을 값을 다룬다. 외부에서 온 값(요청 경로, 헤더, 외부 응답)에 사용한다.
public final class LogValues {

    private LogValues() {
    }

    /**
     * 따옴표로 감싼 logfmt 값(key="{}")에 넣을 수 있도록 바꾼다.
     * 큰따옴표·역슬래시로 값을 끝내고 키를 끼워 넣거나, 개행으로 로그 줄을 나누지 못하게 한다.
     * null은 "null" 문자열 대신 빈 값으로 남긴다.
     */
    public static String quoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\\', '/').replace('"', '\'').replaceAll("[\\r\\n\\t]", " ");
    }
}
