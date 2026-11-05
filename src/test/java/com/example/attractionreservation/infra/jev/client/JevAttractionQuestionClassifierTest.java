package com.example.attractionreservation.infra.jev.client;

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import com.example.attractionreservation.domain.attraction.port.dto.QuestionClassification;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.Usage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

// 어댑터가 하는 일만 검증한다: 질문을 Choice로 바꾸고, 응답을 분류 결과로 바꾸고, 실패를 503으로 바꾼다.
// 실제 HTTP 동작(재시도, 타임아웃, 잘못된 JSON)은 JevClientHttpTest에서 검증한다.
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class JevAttractionQuestionClassifierTest {

    private static final String QUESTION = "모니모러쉬 키제한 있어?";
    private static final Long AMAZON_ID = 1L;
    private static final Long MONIMO_ID = 2L;
    private static final List<AttractionCandidate> CANDIDATES = List.of(
            new AttractionCandidate(AMAZON_ID, "아마존 익스프레스"),
            new AttractionCandidate(MONIMO_ID, "모니모 러쉬"));

    @Mock
    private ObjectProvider<TypeSafeClient> typeSafeClientProvider;

    @Mock
    private TypeSafeClient typeSafeClient;

    @Captor
    private ArgumentCaptor<Map<String, Choice>> questionsCaptor;

    @InjectMocks
    private JevAttractionQuestionClassifier jevAttractionQuestionClassifier;

    private void givenClient() {
        given(typeSafeClientProvider.getIfAvailable()).willReturn(typeSafeClient);
    }

    private void givenResponse(ChoiceAnswer attraction, ChoiceAnswer intent) {
        givenClient();
        given(typeSafeClient.systemOne(anyString(), anyMap())).willReturn(new SystemOneResponse(
                "jev-1.13.0", Map.of("attraction", attraction, "intent", intent), null));
    }

    private ChoiceAnswer attractionAnswer(String option) {
        return new ChoiceAnswer(option, Map.of(option, 0.9), 0.8);
    }

    private ChoiceAnswer intentAnswer(String option) {
        return new ChoiceAnswer(option, Map.of(option, 0.9), 0.7);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE));
    }

    @Test
    @DisplayName("질문은 state로, 어트랙션과 의도는 Choice 두 개로 보낸다")
    void classify_sendsQuestionAndOptions() {
        // given
        givenResponse(attractionAnswer("2"), intentAnswer("HEIGHT_LIMIT"));

        // when
        jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES);

        // then
        verify(typeSafeClient).systemOne(eq(QUESTION), questionsCaptor.capture());
        Map<String, Choice> questions = questionsCaptor.getValue();
        assertThat(questions).containsOnlyKeys("attraction", "intent");
        assertThat(questions.get("attraction").criteria().keySet()).containsExactly("1", "2", "NONE");
        assertThat(questions.get("attraction").criteria().get("2").asText()).isEqualTo("모니모 러쉬");
        assertThat(questions.get("intent").criteria().keySet())
                .containsExactly("HEIGHT_LIMIT", "OPERATING_HOURS", "OPERATION_STATUS", "OTHER");
    }

    @Test
    @DisplayName("Jev 응답을 분류 결과로 바꾼다. 없음(NONE)의 확률은 어트랙션이 아니라서 뺀다")
    void classify_convertsResponse() {
        // given
        givenResponse(
                new ChoiceAnswer("2", Map.of("1", 0.05, "2", 0.9, "NONE", 0.05), 0.85),
                new ChoiceAnswer("HEIGHT_LIMIT", Map.of("HEIGHT_LIMIT", 0.8, "OTHER", 0.2), 0.73));

        // when
        QuestionClassification result = jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES);

        // then
        assertThat(result).isEqualTo(new QuestionClassification(
                MONIMO_ID, 0.85, Map.of(AMAZON_ID, 0.05, MONIMO_ID, 0.9), QuestionIntent.HEIGHT_LIMIT, 0.73));
    }

    @Test
    @DisplayName("없음(NONE)을 고르면 어트랙션 ID는 null이고, 후보를 고를 수 있게 확률은 넘긴다")
    void classify_noAttraction() {
        // given
        givenResponse(
                new ChoiceAnswer("NONE", Map.of("1", 0.2, "2", 0.3, "NONE", 0.5), 0.25),
                intentAnswer("HEIGHT_LIMIT"));

        // when
        QuestionClassification result = jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES);

        // then
        assertThat(result.attractionId()).isNull();
        assertThat(result.attractionProbabilities()).containsOnly(Map.entry(AMAZON_ID, 0.2), Map.entry(MONIMO_ID, 0.3));
    }

    @ParameterizedTest
    @EnumSource(QuestionIntent.class)
    @DisplayName("Choice 옵션 이름을 의도로 바꾼다")
    void classify_convertsIntent(QuestionIntent intent) {
        // given
        givenResponse(attractionAnswer("2"), intentAnswer(intent.name()));

        // when
        QuestionClassification result = jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES);

        // then
        assertThat(result.intent()).isEqualTo(intent);
    }

    @ParameterizedTest(name = "[{index}] 어트랙션 {0}, 의도 {1}")
    @CsvSource({
            "99,  HEIGHT_LIMIT",
            "abc, HEIGHT_LIMIT",
            "2,   UNKNOWN"
    })
    @DisplayName("보내지 않은 옵션이 돌아오면 추측하지 않고 503으로 응답한다")
    void classify_unexpectedOption(String attractionOption, String intentOption) {
        // given
        givenResponse(attractionAnswer(attractionOption), intentAnswer(intentOption));

        // when, then
        assertUnavailable();
    }

    @Test
    @DisplayName("응답에 답이 빠져 있으면 503으로 응답한다")
    void classify_missingAnswer() {
        // given
        givenClient();
        given(typeSafeClient.systemOne(anyString(), anyMap())).willReturn(new SystemOneResponse(
                "jev-1.13.0", Map.of("attraction", attractionAnswer("2")), null));

        // when, then
        assertUnavailable();
    }

    @Test
    @DisplayName("API 키가 없어 클라이언트가 없으면 503으로 응답한다 (시나리오 14)")
    void classify_noClient() {
        // given
        given(typeSafeClientProvider.getIfAvailable()).willReturn(null);

        // when, then
        assertUnavailable();
    }

    @Test
    @DisplayName("Jev 호출이 실패하면 503으로 응답한다 (시나리오 15)")
    void classify_clientFailure() {
        // given
        givenClient();
        given(typeSafeClient.systemOne(anyString(), anyMap())).willThrow(new TypeSafeException("실패"));

        // when, then
        assertUnavailable();
    }

    @Test
    @DisplayName("Jev 연결이 끊기면 503으로 응답한다 (시나리오 15)")
    void classify_connectionFailure() {
        // given
        givenClient();
        given(typeSafeClient.systemOne(anyString(), anyMap()))
                .willThrow(new TypeSafeApiConnectionException("연결 실패", null));

        // when, then
        assertUnavailable();
    }

    @Test
    @DisplayName("Jev 응답이 늦으면 503으로 응답한다 (시나리오 15)")
    void classify_timeout() {
        // given
        givenClient();
        given(typeSafeClient.systemOne(anyString(), anyMap()))
                .willThrow(new TypeSafeApiTimeoutException("시간 초과", Duration.ofSeconds(2), null));

        // when, then
        assertUnavailable();
    }

    @ParameterizedTest(name = "[{index}] 옵션 {0}이면 attractionId={1}")
    @CsvSource({
            "2,    2",
            "NONE, null"
    })
    @DisplayName("분류에 성공하면 소요 시간, 어트랙션, 확신도, 의도, 모델을 로그로 남긴다 (시나리오 16)")
    void classify_logsClassification(String attractionOption, String loggedAttractionId, CapturedOutput output) {
        // given
        givenResponse(
                new ChoiceAnswer(attractionOption, Map.of(attractionOption, 0.9), 0.85),
                new ChoiceAnswer("HEIGHT_LIMIT", Map.of("HEIGHT_LIMIT", 0.8), 0.73));

        // when
        jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES);

        // then
        assertThat(output)
                .contains("event=ATTRACTION_QNA_CLASSIFIED target=jev durationMs=")
                .contains("questionLength=" + QUESTION.length() + " attractionId=" + loggedAttractionId
                        + " attractionConfidence=0.85 intent=HEIGHT_LIMIT intentConfidence=0.73 model=\"jev-1.13.0\"");
    }

    @ParameterizedTest(name = "[{index}] 입력 토큰 {0}이면 inputTokens={1}")
    @CsvSource(value = {
            "120,  120",
            "NULL, null"
    }, nullValues = "NULL")
    @DisplayName("Jev가 알려 준 입력 토큰 수를 로그로 남기고, 알려 주지 않으면 0이 아니라 null로 남긴다")
    void classify_logsInputTokens(Integer inputTokens, String loggedInputTokens, CapturedOutput output) {
        // given
        givenClient();
        given(typeSafeClient.systemOne(anyString(), anyMap())).willReturn(new SystemOneResponse(
                "jev-1.13.0",
                Map.of("attraction", attractionAnswer("2"), "intent", intentAnswer("HEIGHT_LIMIT")),
                new Usage(inputTokens, 3)));

        // when
        jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES);

        // then
        assertThat(output).contains("model=\"jev-1.13.0\" inputTokens=" + loggedInputTokens);
    }

    @Test
    @DisplayName("질문 원문은 로그에 남기지 않고 길이만 남긴다 (시나리오 16)")
    void classify_doesNotLogQuestion(CapturedOutput output) {
        // given
        String question = "우리 딸 지우 키 105인데 썬더폴스 탈 수 있어?";
        givenResponse(attractionAnswer("2"), intentAnswer("HEIGHT_LIMIT"));

        // when
        jevAttractionQuestionClassifier.classify(question, CANDIDATES);

        // then
        assertThat(output)
                .contains("questionLength=" + question.length())
                .doesNotContain("지우")
                .doesNotContain("썬더폴스");
    }

    @Test
    @DisplayName("Jev가 보낸 모델 이름은 로그 키를 끼워 넣지 못하게 바꿔서 남긴다")
    void classify_modelWithQuote_logEscaped(CapturedOutput output) {
        // given
        givenClient();
        given(typeSafeClient.systemOne(anyString(), anyMap())).willReturn(new SystemOneResponse(
                "jev\" admin=\"true",
                Map.of("attraction", attractionAnswer("2"), "intent", intentAnswer("HEIGHT_LIMIT")),
                null));

        // when
        jevAttractionQuestionClassifier.classify(QUESTION, CANDIDATES);

        // then
        assertThat(output).contains("model=\"jev' admin='true\"");
    }

    @Test
    @DisplayName("API 키가 없으면 원인을 로그로 남긴다 (시나리오 14)")
    void classify_noClient_logsReason(CapturedOutput output) {
        // given
        given(typeSafeClientProvider.getIfAvailable()).willReturn(null);

        // when, then
        assertUnavailable();
        assertThat(output).contains(
                "event=ATTRACTION_QNA_JEV_FAILED target=jev reason=\"API 키 미설정\" errorCode=ATTRACTION_QUESTION_UNAVAILABLE");
    }

    @Test
    @DisplayName("보내지 않은 옵션이 돌아오면 응답 값 없이 예외 종류만 로그로 남긴다")
    void classify_unexpectedOption_logsExceptionTypeOnly(CapturedOutput output) {
        // given
        givenResponse(attractionAnswer("응답값"), intentAnswer("HEIGHT_LIMIT"));

        // when, then
        assertUnavailable();
        assertThat(output)
                .contains("event=ATTRACTION_QNA_JEV_FAILED target=jev durationMs=")
                .contains("exceptionType=IllegalArgumentException errorCode=ATTRACTION_QUESTION_UNAVAILABLE")
                .doesNotContain("응답값");
    }
}
