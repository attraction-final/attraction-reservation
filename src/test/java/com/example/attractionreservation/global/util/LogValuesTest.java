package com.example.attractionreservation.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogValuesTest {

    @Test
    @DisplayName("큰따옴표와 역슬래시는 값을 끝내지 못하도록 다른 문자로 바꾼다.")
    void quoted_quoteAndBackslash_Replaced() {
        //given
        String value = "msg\\\" event=FAKE";

        //when
        String result = LogValues.quoted(value);

        //then
        assertThat(result).isEqualTo("msg/' event=FAKE");
    }

    @Test
    @DisplayName("개행과 탭은 로그 줄을 나누지 못하도록 공백으로 바꾼다.")
    void quoted_lineBreak_ReplacedWithSpace() {
        //given
        String value = "a\r\nb\tc";

        //when
        String result = LogValues.quoted(value);

        //then
        assertThat(result).isEqualTo("a  b c");
    }

    @Test
    @DisplayName("값이 없으면 null 문자열 대신 빈 값을 반환한다.")
    void quoted_null_Empty() {
        //when
        String result = LogValues.quoted(null);

        //then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("바꿀 문자가 없는 값은 그대로 반환한다.")
    void quoted_plainValue_Unchanged() {
        //when
        String result = LogValues.quoted("/api/attractions/foo=bar");

        //then
        assertThat(result).isEqualTo("/api/attractions/foo=bar");
    }
}
