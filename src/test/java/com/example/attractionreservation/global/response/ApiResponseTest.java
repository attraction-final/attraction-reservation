package com.example.attractionreservation.global.response;

import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("성공 응답 JSON에는 code가 없다 (기존 응답 형식 그대로)")
    void ok_HasNoCode() throws Exception {
        String json = objectMapper.writeValueAsString(ApiResponse.ok("성공", 1));

        assertThat(json).doesNotContain("\"code\"")
                .contains("\"success\":true", "\"message\":\"성공\"", "\"data\":1");
    }

    @Test
    @DisplayName("실패 응답 JSON에는 ErrorCode 이름이 code로 들어간다")
    void error_HasErrorCodeName() throws Exception {
        String json = objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.ATTRACTION_NOT_FOUND));

        assertThat(json).contains("\"success\":false", "\"code\":\"ATTRACTION_NOT_FOUND\"");
    }
}
