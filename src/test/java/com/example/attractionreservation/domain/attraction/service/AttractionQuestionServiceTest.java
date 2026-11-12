package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionQuestionResponse;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionAnswerType;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import com.example.attractionreservation.domain.attraction.port.AttractionQuestionClassifier;
import com.example.attractionreservation.domain.attraction.port.dto.QuestionClassification;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
//  분류된 어트랙션이 DB 목록에 없음     → NEED_CLARIFICATION (되묻기)
// 분기 규칙만 검증한다. 문구 조립은 AttractionAnswerComposerTest, Jev 호출은 어댑터 테스트에서 검증하기
@ExtendWith(MockitoExtension.class)
class AttractionQuestionServiceTest {

    private static final String QUESTION = "모니모러쉬 키제한 있어?";
    private static final Long MONIMO_ID = 1L;
    private static final Long HURRICANE_ID = 2L;
    private static final Long ROLLING_ID = 3L;
    private static final Long BUMPER_ID = 4L;

    @Mock
    private AttractionRepository attractionRepository;

    @Mock
    private AttractionQuestionClassifier attractionQuestionClassifier;

    @Mock
    private AttractionAnswerComposer attractionAnswerComposer;

    @InjectMocks
    private AttractionQuestionService attractionQuestionService;

    private Attraction monimo;
    private Attraction hurricane;
    private Attraction rolling;
    private Attraction bumper;

    @BeforeEach
    void setUp() {
        monimo = createAttraction(MONIMO_ID, "모니모 러쉬");
        hurricane = createAttraction(HURRICANE_ID, "허리케인");
        rolling = createAttraction(ROLLING_ID, "롤링 엑스 트레인");
        bumper = createAttraction(BUMPER_ID, "범퍼카");

        given(attractionRepository.findAllByDeletedAtIsNullOrderByIdAsc())
                .willReturn(List.of(monimo, hurricane, rolling, bumper));
    }

    // DB에 저장하지 않은 엔티티는 id가 null이라서 직접 넣어 줌
    private Attraction createAttraction(Long id, String name) {
        Attraction attraction = Attraction.create(name, "설명", "A구역", 10, 5);
        ReflectionTestUtils.setField(attraction, "id", id);
        return attraction;
    }

    private void givenClassification(QuestionClassification classification) {
        given(attractionQuestionClassifier.classify(anyString(), anyList())).willReturn(classification);
    }

    // 후보 규칙과 상관없는 테스트에서 쓰는 분류 결과
    private QuestionClassification classification(
            QuestionIntent intent, double intentConfidence, Long attractionId, double attractionConfidence) {
        return new QuestionClassification(attractionId, attractionConfidence, Map.of(), intent, intentConfidence);
    }

    // 후보 규칙 테스트에서 쓰는 분류 결과 어트랙션을 특정하지 못한 상황이다.
    private QuestionClassification ambiguous(Map<Long, Double> attractionProbabilities) {
        return new QuestionClassification(null, 0.0, attractionProbabilities, QuestionIntent.HEIGHT_LIMIT, 0.9);
    }

    @Test
    @DisplayName("분류기에는 질문과 삭제되지 않은 어트랙션 목록을 그대로 넘긴다")
    void classify_receivesQuestionAndAttractions() {
        givenClassification(classification(QuestionIntent.HEIGHT_LIMIT, 0.9, MONIMO_ID, 0.9));

        attractionQuestionService.answer(QUESTION);

        verify(attractionQuestionClassifier).classify(QUESTION, List.of(
                new AttractionCandidate(MONIMO_ID, "모니모 러쉬"),
                new AttractionCandidate(HURRICANE_ID, "허리케인"),
                new AttractionCandidate(ROLLING_ID, "롤링 엑스 트레인"),
                new AttractionCandidate(BUMPER_ID, "범퍼카")));
    }

    // 판정 순서가 곧 정책이라 조합을 표 하나에 모아 둔다. 0.6은 경계값으로 "확실함"에 포함된다.
    // 표의 attractionId가 null이면 Jev가 어트랙션 없음(NONE)으로 분류한 경우다.
    @ParameterizedTest(name = "[{index}] {5}")
    @CsvSource(nullValues = "null", textBlock = """
            OTHER,            0.9,  1,    0.9,  UNSUPPORTED,        시나리오 9 - 지원 범위 밖 질문
            OTHER,            0.6,  1,    0.9,  UNSUPPORTED,        OTHER 확신도 경계값
            OTHER,            0.9,  null, 0.9,  UNSUPPORTED,        어트랙션이 없어도 OTHER가 먼저
            OTHER,            0.9,  1,    0.1,  UNSUPPORTED,        어트랙션이 모호해도 OTHER가 먼저
            HEIGHT_LIMIT,     0.9,  1,    0.59, NEED_CLARIFICATION, 시나리오 6 - 어트랙션 확신도 경계값 미만
            HEIGHT_LIMIT,     0.9,  null, 0.9,  NEED_CLARIFICATION, 시나리오 7 - 어트랙션 언급 없음
            OTHER,            0.59, null, 0.9,  NEED_CLARIFICATION, OTHER가 불확실하면 미지원으로 단정하지 않음
            HEIGHT_LIMIT,     0.59, 1,    0.59, NEED_CLARIFICATION, 둘 다 불확실하면 어트랙션부터 되묻기
            HEIGHT_LIMIT,     0.59, 1,    0.6,  ANSWERED,           시나리오 8 - 의도 확신도 경계값 미만이면 요약
            OTHER,            0.59, 1,    0.9,  ANSWERED,           OTHER가 불확실하면 요약
            HEIGHT_LIMIT,     0.6,  1,    0.6,  ANSWERED,           시나리오 1 - 둘 다 경계값이면 답변
            OPERATING_HOURS,  0.9,  1,    0.9,  ANSWERED,           시나리오 2 - 운영 시간
            OPERATION_STATUS, 0.9,  1,    0.9,  ANSWERED,           시나리오 3 - 운영 상태
            """)
    @DisplayName("분류 결과에 따라 응답 type을 정한다")
    void answer_decidesTypeFromClassification(
            QuestionIntent intent, double intentConfidence,
            Long attractionId, double attractionConfidence,
            QuestionAnswerType expectedType, String description) {
        givenClassification(classification(intent, intentConfidence, attractionId, attractionConfidence));

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.type()).isEqualTo(expectedType);
    }

    @Test
    @DisplayName("의도와 어트랙션이 확실하면 그 어트랙션의 문구로 답한다 (시나리오 1)")
    void answer_answered() {
        givenClassification(classification(QuestionIntent.HEIGHT_LIMIT, 0.9, MONIMO_ID, 0.9));
        given(attractionAnswerComposer.compose(monimo, QuestionIntent.HEIGHT_LIMIT)).willReturn("키 제한 답변");

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.type()).isEqualTo(QuestionAnswerType.ANSWERED);
        assertThat(response.intent()).isEqualTo(QuestionIntent.HEIGHT_LIMIT);
        assertThat(response.attractionId()).isEqualTo(MONIMO_ID);
        assertThat(response.attractionName()).isEqualTo("모니모 러쉬");
        assertThat(response.answer()).isEqualTo("키 제한 답변");
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    @DisplayName("의도가 불확실하면 intent 없이 요약으로 답한다 (시나리오 8)")
    void answer_summary() {
        givenClassification(classification(QuestionIntent.HEIGHT_LIMIT, 0.59, MONIMO_ID, 0.9));
        given(attractionAnswerComposer.composeSummary(monimo)).willReturn("요약 답변");

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.type()).isEqualTo(QuestionAnswerType.ANSWERED);
        assertThat(response.intent()).isNull();
        assertThat(response.attractionId()).isEqualTo(MONIMO_ID);
        assertThat(response.answer()).isEqualTo("요약 답변");
    }

    @Test
    @DisplayName("지원하지 않는 질문은 고정 안내로 답하고 문구를 조립하지 않는다 (시나리오 9)")
    void answer_unsupported() {
        givenClassification(classification(QuestionIntent.OTHER, 0.9, MONIMO_ID, 0.9));

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.answer()).isEqualTo("지금은 어트랙션의 키 제한, 운영시간, 운영 상태만 안내할 수 있습니다.");
        assertThat(response.attractionId()).isNull();
        verifyNoInteractions(attractionAnswerComposer);
    }

    @Test
    @DisplayName("어트랙션이 모호하면 확률 높은 순으로 최대 3개를 되묻고 키 수치를 추측하지 않는다 (시나리오 6)")
    void answer_needClarification_topThreeCandidates() {
        givenClassification(ambiguous(Map.of(
                MONIMO_ID, 0.15,
                HURRICANE_ID, 0.40,
                ROLLING_ID, 0.25,
                BUMPER_ID, 0.20)));

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.answer()).contains("어떤 어트랙션을 말씀하시는지 확실하지 않습니다");
        assertThat(response.candidates()).containsExactly(
                new AttractionCandidate(HURRICANE_ID, "허리케인"),
                new AttractionCandidate(ROLLING_ID, "롤링 엑스 트레인"),
                new AttractionCandidate(BUMPER_ID, "범퍼카"));
        verifyNoInteractions(attractionAnswerComposer);
    }

    @Test
    @DisplayName("확률이 하한 미만인 어트랙션은 후보에서 뺀다")
    void answer_needClarification_excludesLowProbability() {
        givenClassification(ambiguous(Map.of(
                HURRICANE_ID, 0.45,
                ROLLING_ID, 0.10,
                BUMPER_ID, 0.09)));

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.candidates()).containsExactly(
                new AttractionCandidate(HURRICANE_ID, "허리케인"),
                new AttractionCandidate(ROLLING_ID, "롤링 엑스 트레인"));
    }

    @Test
    @DisplayName("후보로 꼽을 어트랙션이 없으면 전체 목록을 선택지로 준다 (시나리오 7)")
    void answer_needClarification_allAttractionsWhenNoCandidate() {
        givenClassification(ambiguous(Map.of(MONIMO_ID, 0.02, HURRICANE_ID, 0.01)));

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.candidates()).hasSize(4);
    }

    @Test
    @DisplayName("분류된 어트랙션이 조회 목록에 없으면 추측하지 않고 되묻는다")
    void answer_needClarification_whenClassifiedAttractionIsMissing() {
        givenClassification(classification(QuestionIntent.HEIGHT_LIMIT, 0.9, 999L, 0.9));

        CreateAttractionQuestionResponse response = attractionQuestionService.answer(QUESTION);

        assertThat(response.type()).isEqualTo(QuestionAnswerType.NEED_CLARIFICATION);
        verifyNoInteractions(attractionAnswerComposer);
    }

    @Test
    @DisplayName("분류기가 실패하면 예외를 그대로 올려 503으로 응답하게 한다 (시나리오 14, 15)")
    void answer_classifierFails() {
        given(attractionQuestionClassifier.classify(any(), any()))
                .willThrow(new BusinessException(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE));

        assertThatThrownBy(() -> attractionQuestionService.answer(QUESTION))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE);
    }
}