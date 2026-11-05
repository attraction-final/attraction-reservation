package com.example.attractionreservation.infra.jev.client;

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import com.example.attractionreservation.domain.attraction.port.dto.QuestionClassification;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.autoconfigure.TypeSafeAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Mock으로는 재시도 횟수, 타임아웃, 잘못된 JSON 처리를 확인할 수 없다.
// 스타터 자동 설정과 application.yaml을 그대로 쓰고, Jev 자리에 로컬 HTTP 서버만 둔다. Docker와 실제 API 키는 필요 없다.
@ExtendWith(OutputCaptureExtension.class)
class JevClientHttpTest {

    private static final String API_KEY = "test-key";
    private static final String QUESTION = "모니모러쉬 키제한 있어?";
    private static final String FAILED_LOG = "event=ATTRACTION_QNA_JEV_FAILED target=jev durationMs=";
    private static final List<AttractionCandidate> CANDIDATES = List.of(
            new AttractionCandidate(1L, "아마존 익스프레스"),
            new AttractionCandidate(2L, "모니모 러쉬"));
    private static final String SUCCESS_BODY = """
            {
              "model": "jev-1.13.0",
              "answers": {
                "attraction": {
                  "type": "choice",
                  "choice": "2",
                  "confidence": 0.85,
                  "probabilities": {"1": 0.05, "2": 0.9, "NONE": 0.05}
                },
                "intent": {
                  "type": "choice",
                  "choice": "HEIGHT_LIMIT",
                  "confidence": 0.73,
                  "probabilities": {"HEIGHT_LIMIT": 0.8, "OPERATING_HOURS": 0.1, "OPERATION_STATUS": 0.05, "OTHER": 0.05}
                }
              },
              "usage": {"input_tokens": 120, "output_tokens": 3}
            }
            """;

    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    private HttpServer server;
    private ExecutorService serverExecutor;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class))
            .withUserConfiguration(JevAttractionQuestionClassifier.class);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(); // 재시도 요청이 앞선 요청에 막히지 않게 한다
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private ApplicationContextRunner withApiKey() {
        return contextRunner.withPropertyValues(
                "spring.ai.typesafe.api-key=" + API_KEY,
                "spring.ai.typesafe.base-url=http://localhost:" + server.getAddress().getPort());
    }

    private void respondWith(int status, String body) {
        server.createContext("/v1/systemone", exchange -> {
            recordRequest(exchange);
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    private void respondTooLate() {
        server.createContext("/v1/systemone", exchange -> {
            recordRequest(exchange);
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
    }

    private void recordRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private void assertUnavailable(JevAttractionQuestionClassifier classifier) {
        assertThatThrownBy(() -> classifier.classify(QUESTION, CANDIDATES))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE));
    }

    @Test
    @DisplayName("API 키가 없어도 앱은 뜨고 Q&A만 503으로 응답한다 (시나리오 14)")
    void noApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(TypeSafeClient.class);

            assertUnavailable(context.getBean(JevAttractionQuestionClassifier.class));
        });
    }

    @Test
    @DisplayName("application.yaml의 모델과 시간 제한이 클라이언트에 적용된다")
    void appliesSettings() {
        withApiKey().run(context -> {
            TypeSafeClient client = context.getBean(TypeSafeClient.class);

            assertThat(client.defaultModel()).isEqualTo("jev-1.13.0");
            assertThat(client.timeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(client.retryPolicy().maxRetries()).isEqualTo(1);
            assertThat(client.retryPolicy().totalTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    @DisplayName("키와 모델을 담아 요청하고 응답을 분류 결과로 바꾼다")
    void classify_success(CapturedOutput output) {
        respondWith(200, SUCCESS_BODY);

        withApiKey().run(context -> {
            QuestionClassification result = context.getBean(JevAttractionQuestionClassifier.class)
                    .classify(QUESTION, CANDIDATES);

            assertThat(result.attractionId()).isEqualTo(2L);
            assertThat(result.attractionConfidence()).isEqualTo(0.85);
            assertThat(result.intent()).isEqualTo(QuestionIntent.HEIGHT_LIMIT);
            assertThat(result.intentConfidence()).isEqualTo(0.73);

            String body = requestBody.get();
            assertThat(authorization.get()).isEqualTo("Bearer " + API_KEY);
            assertThat((String) JsonPath.read(body, "$.model")).isEqualTo("jev-1.13.0");
            assertThat((String) JsonPath.read(body, "$.state")).isEqualTo(QUESTION);
            assertThat((String) JsonPath.read(body, "$.questions.attraction.type")).isEqualTo("choice");
            assertThat((String) JsonPath.read(body, "$.questions.attraction.criteria['2']")).isEqualTo("모니모 러쉬");
            assertThat(output)
                    .contains("event=ATTRACTION_QNA_CLASSIFIED target=jev durationMs=")
                    .contains("model=\"jev-1.13.0\" inputTokens=120");
        });
    }

    @ParameterizedTest(name = "[{index}] HTTP {0}이면 {1}번 호출, {2}")
    @CsvSource({
            "400, 1, TypeSafeBadRequestException",
            "401, 1, TypeSafeAuthenticationException",
            "422, 1, TypeSafeUnprocessableEntityException",
            "429, 2, TypeSafeRateLimitException",
            "500, 2, TypeSafeInternalServerException",
            "529, 2, TypeSafeOverloadedException"
    })
    @DisplayName("Jev 오류 응답은 503이 되고, 일시적인 오류만 한 번 재시도하며, 예외 종류를 로그로 남긴다 (시나리오 15)")
    void classify_errorStatus(int status, int expectedRequests, String exceptionType, CapturedOutput output) {
        respondWith(status, "{}");

        withApiKey().run(context -> {
            assertUnavailable(context.getBean(JevAttractionQuestionClassifier.class));

            assertThat(requestCount.get()).isEqualTo(expectedRequests);
            assertThat(output).contains(FAILED_LOG).contains("exceptionType=" + exceptionType);
        });
    }

    @Test
    @DisplayName("Jev가 오류 응답에 질문을 되돌려줘도 실패 로그에는 남기지 않는다 (시나리오 16)")
    void classify_errorBodyEchoesQuestion(CapturedOutput output) {
        respondWith(422, "{\"detail\": \"처리할 수 없는 질문입니다: " + QUESTION + "\"}");

        withApiKey().run(context -> {
            assertUnavailable(context.getBean(JevAttractionQuestionClassifier.class));

            // 테스트의 DEBUG 로그는 운영에 남지 않으므로 실패 로그부터 끝까지만 본다. 예외 객체를 넘기면 스택 트레이스가 여기에 나온다.
            String failureLog = output.getOut().substring(output.getOut().indexOf(FAILED_LOG));
            assertThat(failureLog).doesNotContain(QUESTION);
        });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            JSON이 아님 | not-json
            답이 없음   | {"model": "jev-1.13.0", "answers": {}}
            형식이 다름 | {"model": "jev-1.13.0", "answers": {"attraction": {"type": "score", "score": 0.5}, "intent": {"type": "score", "score": 0.5}}}
            선택지 없음 | {"model": "jev-1.13.0", "answers": {"attraction": {"type": "choice", "choice": "2", "confidence": 0.8, "probabilities": {"2": 0.9}}, "intent": {"type": "choice", "confidence": 0.8, "probabilities": {"OTHER": 0.9}}}}
            """)
    @DisplayName("200이어도 응답을 해석할 수 없으면 503으로 응답한다")
    void classify_invalidBody(String description, String body, CapturedOutput output) {
        respondWith(200, body);

        withApiKey().run(context -> {
            assertUnavailable(context.getBean(JevAttractionQuestionClassifier.class));

            assertThat(output).contains(FAILED_LOG);
        });
    }

    @Test
    @DisplayName("Jev 응답이 늦어도 재시도를 포함해 5초 안에 503으로 응답한다 (시나리오 15)")
    void classify_timeout(CapturedOutput output) {
        respondTooLate();

        withApiKey().run(context -> {
            JevAttractionQuestionClassifier classifier = context.getBean(JevAttractionQuestionClassifier.class);

            long startedAt = System.nanoTime();
            assertUnavailable(classifier);

            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
            assertThat(requestCount.get()).isEqualTo(2);
            assertThat(output).contains(FAILED_LOG).contains("exceptionType=TypeSafeApiTimeoutException");
        });
    }

    @Test
    @DisplayName("Jev에 연결할 수 없으면 503으로 응답한다 (시나리오 15)")
    void classify_connectionRefused(CapturedOutput output) {
        ApplicationContextRunner runner = withApiKey();
        server.stop(0);

        runner.run(context -> {
            assertUnavailable(context.getBean(JevAttractionQuestionClassifier.class));

            assertThat(output).contains(FAILED_LOG).contains("exceptionType=TypeSafeApiConnectionException");
        });
    }
}
