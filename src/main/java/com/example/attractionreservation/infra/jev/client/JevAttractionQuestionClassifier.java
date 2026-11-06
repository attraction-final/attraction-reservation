package com.example.attractionreservation.infra.jev.client;

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import com.example.attractionreservation.domain.attraction.port.AttractionQuestionClassifier;
import com.example.attractionreservation.domain.attraction.port.dto.QuestionClassification;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

//api키가 없으면 스타터가 TypesafeClient 빈을 만들지 않는다.
//키가 없어도 앱은 떠야 하므로 ObjectProvider로 받고 , 호출 시점에 큐엔에이만 503으로 응답한다.

@Component
@RequiredArgsConstructor
public class JevAttractionQuestionClassifier implements AttractionQuestionClassifier {

    private static final String ATTRACTION_QUESTION = "attraction";
    private static final String INTENT_QUESTION = "intent";
    private static final String NO_ATTRACTION = "NONE";

    //jev문서에 한국어는 영어보다 정확도가 낮다고 되어 있어 지시문은 영어로 쓴다
    //어트랙션 이름과 질문은 한국어 그대로 보낸다.

    private static final String ATTRACTION_INSTRUCTIONS =
            "which amusement park attraction is the visitor asking about? "
                    + "Attraction names may be shortened or written with different spacing.";

    private static final String NO_ATTRACTION_DESCRIPTION =
            "The visitor does not name one of the listed attractions.";

    private static final String INTENT_INSTRUCTIONS =
            "What does the visitor want to know about the attraction?";

    private final ObjectProvider<TypeSafeClient> typeSafeClientProvider;

    @Override
    public QuestionClassification classify(String question, List<AttractionCandidate> candidates) {
        TypeSafeClient typeSafeClient = typeSafeClientProvider.getIfAvailable();
        if (typeSafeClient == null) {
            throw new BusinessException(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE);
        }
        try {
            //어트랙션과 의도를 한 요청으로 묻는다. jev는 입력 토큰으로 과급되므로 두 번 호출하지 않는다.
            SystemOneResponse response = typeSafeClient.systemOne(question, Map.of(
                    ATTRACTION_QUESTION, attractionQuestion(candidates),
                    INTENT_QUESTION, intentQuestion()));

            return toClassification(response, candidates);
        } catch (TypeSafeException | IllegalArgumentException e) {
            // 멀티 캐치
            //키 오류, 호출 한도 , 타임아웃, 해석할 수 없는 응답을 구분하지 않고 재시도 안내 503롷 응답한다.
            //사용자가 할 수 있는 일은 같고, 잘못된 값으로 숫자를 답하는 것보다 낫다. 실패 원인은 6단계 로그로 남긴다.
            throw new BusinessException(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE);
        }
    }
    //옵션 이름은 어트랙션 id, 설명은 어트랙션 이름으로 둔다. jev는 둘 다 본다.
    //id를 쓰면 이름이 바뀌어도, 공백이나 특수문자가 있어도 응답을 그대로 id로 되돌릴 수 있다.
    //jev는 먼저 나온 옵션을 선호하는 경향이 잇다
    private Choice attractionQuestion(List<AttractionCandidate> candidates) {
        Choice.Builder builder = Choice.builder().instructions(ATTRACTION_INSTRUCTIONS);
        candidates.forEach(candidate -> builder.option(optionName(candidate), candidate.name()));
        return builder.option(NO_ATTRACTION, NO_ATTRACTION_DESCRIPTION).build();
    }

    private String optionName(AttractionCandidate candidate) {
        return String.valueOf(candidate.attractionId());
    }

    private Choice intentQuestion() {
        Choice.Builder builder = Choice.builder().instructions(INTENT_INSTRUCTIONS);
        for (QuestionIntent intent : QuestionIntent.values()) {
            builder.option(intent.name(), describe(intent));
        }
        return builder.build();
    }

    private String describe(QuestionIntent intent) {
        return switch (intent) {
            case HEIGHT_LIMIT -> "Height limits, or whether a child or person of some height can ride";
            case OPERATING_HOURS -> "Opening time, closing time, or how late the attraction runs";
            case OPERATION_STATUS -> "Whether the attraction is running or can be ridden right now";
            case OTHER -> "Anything else, such as price, location, or waiting time";
        };
    }
    //jev 문서는 Choice에서 confidence보다  선택지의 확률로 판단하라고 안내한다.
    private QuestionClassification toClassification(SystemOneResponse response, List<AttractionCandidate> candidates) {
        ChoiceAnswer attraction = response.choice(ATTRACTION_QUESTION);
        ChoiceAnswer intent = response.choice(INTENT_QUESTION);

        return new QuestionClassification(
                toAttractionId(attraction, candidates),
                attraction.confidence(),
                toAttractionProbabilities(attraction, candidates),
                toIntent(intent),
                intent.confidence());
    }

    private Long toAttractionId(ChoiceAnswer attraction, List<AttractionCandidate> candidates) {
        if (NO_ATTRACTION.equals(attraction.value())) {
            return null;
        }
        return candidates.stream()
                .filter(candidate -> optionName(candidate).equals(attraction.value()))
                .map(AttractionCandidate::attractionId)
                .findFirst()
                .orElseThrow(() ->new IllegalArgumentException("보내지 않은 옵션입니다: " + attraction.value()));
    }

    private Map<Long, Double> toAttractionProbabilities(ChoiceAnswer attraction, List<AttractionCandidate> candidates) {
        return candidates.stream().collect(Collectors.toMap(
                AttractionCandidate::attractionId,
                candidate -> attraction.probabilityOf(optionName(candidate))));
    }

    private QuestionIntent toIntent(ChoiceAnswer intent) {
        return Arrays.stream(QuestionIntent.values())
                .filter(candidate ->candidate.name().equals(intent.value()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("보내지 않은 옵션입니다: " + intent.value()));
    }
}