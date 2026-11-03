package com.example.attractionreservation.global.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("4xx 비즈니스 예외는 요청 정보와 errorCode를 스택 트레이스 없이 INFO로 남긴다")
    void handleBusinessException_ClientError_LogInfoWithoutStackTrace(CapturedOutput output) throws Exception {
        // given
        // when
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound());

        // then
        assertThat(logLine(output, "event=REQUEST_REJECTED"))
                .contains("INFO")
                .contains("method=GET uri=\"/test/not-found\" errorCode=ATTRACTION_NOT_FOUND httpStatus=404");
        assertThat(output).doesNotContain("BusinessException");
    }

    @Test
    @DisplayName("5xx 비즈니스 예외는 알림 대상이므로 ERROR로 남기되, 원인 로그와 중복되지 않도록 스택 트레이스는 남기지 않는다")
    void handleBusinessException_ServerError_LogErrorWithoutStackTrace(CapturedOutput output) throws Exception {
        // given
        // when
        mockMvc.perform(get("/test/bad-gateway"))
                .andExpect(status().isBadGateway());

        // then
        assertThat(logLine(output, "event=REQUEST_FAILED"))
                .contains("ERROR")
                .contains("method=GET uri=\"/test/bad-gateway\"")
                .contains("errorCode=PAYMENT_GATEWAY_ERROR httpStatus=502");
        assertThat(output).doesNotContain("BusinessException");
    }

    @Test
    @DisplayName("요청 본문 검증에 실패하면 INVALID_INPUT_VALUE로 INFO 로그를 남긴다")
    void handleMethodArgumentNotValid_InvalidBody_LogInfo(CapturedOutput output) throws Exception {
        // given
        String body = """
                {"name": ""}
                """;

        // when
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        // then
        assertThat(logLine(output, "event=REQUEST_REJECTED"))
                .contains("INFO")
                .contains("method=POST uri=\"/test/validation\" errorCode=INVALID_INPUT_VALUE httpStatus=400");
    }

    @Test
    @DisplayName("uri에는 쿼리스트링을 남기지 않는다")
    void handleBusinessException_WithQueryString_LogUriWithoutQueryString(CapturedOutput output) throws Exception {
        // given
        // when
        mockMvc.perform(get("/test/not-found").queryParam("token", "secret-token"))
                .andExpect(status().isNotFound());

        // then
        assertThat(logLine(output, "event=REQUEST_REJECTED")).contains("uri=\"/test/not-found\" ");
        assertThat(output).doesNotContain("secret-token");
    }

    @Test
    @DisplayName("요청 경로에 '='가 있어도 logfmt 파싱이 깨지지 않도록 uri를 따옴표로 감싸 남긴다")
    void handleBusinessException_UriWithEquals_LogQuotedUri(CapturedOutput output) throws Exception {
        // given
        // when
        mockMvc.perform(get("/test/not-found/foo=bar"))
                .andExpect(status().isNotFound());

        // then
        assertThat(logLine(output, "event=REQUEST_REJECTED"))
                .contains("method=GET uri=\"/test/not-found/foo=bar\" errorCode=ATTRACTION_NOT_FOUND");
    }

    @Test
    @DisplayName("예상하지 못한 예외는 스택 트레이스와 함께 ERROR로 남긴다")
    void handleException_UnexpectedException_LogErrorWithStackTrace(CapturedOutput output) throws Exception {
        // given
        // when
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError());

        // then
        assertThat(logLine(output, "event=REQUEST_FAILED"))
                .contains("ERROR")
                .contains("method=GET uri=\"/test/unexpected\" errorCode=INTERNAL_SERVER_ERROR httpStatus=500");
        assertThat(output).contains("java.lang.IllegalStateException: 예상하지 못한 오류");
    }

    @Test
    @DisplayName("DB 제약 조건 위반은 스택 트레이스와 함께 ERROR로 남긴다")
    void handleDataIntegrityViolation_ConstraintViolated_LogErrorWithStackTrace(CapturedOutput output) throws Exception {
        // given
        // when
        mockMvc.perform(get("/test/data-integrity"))
                .andExpect(status().isInternalServerError());

        // then
        assertThat(logLine(output, "event=REQUEST_FAILED"))
                .contains("ERROR")
                .contains("errorCode=INTERNAL_SERVER_ERROR httpStatus=500");
        assertThat(output).contains("DataIntegrityViolationException: 제약 조건 위반");
    }

    private String logLine(CapturedOutput output, String event) {
        return output.getAll().lines()
                .filter(line -> line.contains(event))
                .reduce((first, second) -> {
                    throw new AssertionError("로그가 두 번 이상 남았습니다: " + event);
                })
                .orElseThrow(() -> new AssertionError("로그가 없습니다: " + event));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/not-found")
        void notFound() {
            throw new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND);
        }

        @GetMapping("/test/not-found/{value}")
        void notFoundWithPath() {
            throw new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND);
        }

        @GetMapping("/test/bad-gateway")
        void badGateway() {
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }

        @PostMapping("/test/validation")
        void validation(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("예상하지 못한 오류");
        }

        @GetMapping("/test/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException("제약 조건 위반");
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
