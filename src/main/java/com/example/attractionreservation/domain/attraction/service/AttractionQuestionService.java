package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionQuestionResponse;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import com.example.attractionreservation.domain.attraction.port.AttractionQuestionClassifier;
import com.example.attractionreservation.domain.attraction.port.dto.QuestionClassification;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AttractionQuestionService {

    private static final double CONFIDENCE_THRESHOLD = 0.6;

    private static final int MAX_CANDIDATES = 3;

    private static final double MIN_CANDIDATE_PROBABILITY = 0.1;

    private static final String NEED_CLARIFICATION_MESSAGE =
            "어떤 어트랙션을 말씀하시는지 확실하지 않습니다. 목록에서 어트랙션을 골라 다시 질문해 주세요.";

    private static final String UNSUPPORTED_MESSAGE =
            "지금은 어트랙션의 키 제한, 운영시간, 운영 상태만 안내할 수 있습니다.";

    private final AttractionRepository attractionRepository;

    private final AttractionQuestionClassifier attractionQuestionClassifier;

    private final AttractionAnswerComposer attractionAnswerComposer;

    //@Transactional을 붙이지 않음 jev 응답을 기다리는 동안 db 커넥션을 붙잡지 않기 위해서
    public CreateAttractionQuestionResponse answer(String question) {
        List<Attraction> attractions = attractionRepository.findAllByDeletedAtIsNullOrderByIdAsc();
        List<AttractionCandidate> candidates = attractions.stream()
                .map(AttractionCandidate::from)
                .toList();
        QuestionClassification classification = attractionQuestionClassifier.classify(question, candidates);

        //판정 순서가 정책 미지원 -> 되묻기 -> 요약 -> 답변 순으로 본다.
        //의도가 불확실해도 어트랙션만 확실하면 요악을 줄 수 있지만, 어트랙션이 불확실하면 어떤 숫자도 답할 수 없다.
        if (isUnsupported(classification)) {
            return CreateAttractionQuestionResponse.unsupported(UNSUPPORTED_MESSAGE);
        }

        Optional<Attraction> attraction = findConfidentAttraction(classification, attractions);
        if (attraction.isEmpty()) {
            return CreateAttractionQuestionResponse.needClarification
                    (NEED_CLARIFICATION_MESSAGE, pickCandidates(classification, candidates));
        }
        return answerAbout(attraction.get(), classification);
    }

    private boolean isUnsupported(QuestionClassification classification) {
        return classification.intent() == QuestionIntent.OTHER && classification.intentConfidence() >= CONFIDENCE_THRESHOLD;
    }

    //분류된 ID가 목록에 없으면 빈 값으로 돌려 되묻게 한다. 포트 계약상 없어야 할 일이지만, 틀린 어트랙션을 답하는 것보다 나음
    private Optional<Attraction> findConfidentAttraction(QuestionClassification classification, List<Attraction> attractions) {
        if (classification.attractionId() == null || classification.attractionConfidence() < CONFIDENCE_THRESHOLD) {
            return Optional.empty();
        }
        return attractions.stream()
                .filter(
                        attraction -> attraction.getId().equals(classification.attractionId()))
                .findFirst();
    }

    //NONE이 1순위여도 확률이 남은 어트랙션은 후보로 보여준다
    //"롤러코스터"처럼 여러 어트랙션에 걸치는 질문에서 전체 목록보다 좁혀 줄 수 있다.
    private List<AttractionCandidate> pickCandidates(QuestionClassification classification, List<AttractionCandidate> candidates) {
        Map<Long, Double> probabilities = classification.attractionProbabilities();
        List<AttractionCandidate> likelyCandidates = candidates.stream()
                .filter(
                        candidate -> probabilities.getOrDefault(candidate.attractionId(), 0.0)
                                >= MIN_CANDIDATE_PROBABILITY)
                .sorted(Comparator.comparingDouble(
                        (AttractionCandidate candidate) ->
                                probabilities.get(candidate.attractionId())).reversed())
                .limit(MAX_CANDIDATES)
                .toList();
        return likelyCandidates.isEmpty() ? candidates : likelyCandidates;

    }

    private CreateAttractionQuestionResponse answerAbout(Attraction attraction, QuestionClassification classification) {
        if (classification.intentConfidence() < CONFIDENCE_THRESHOLD) {
            return CreateAttractionQuestionResponse.answered(
                    null,
                    attraction.getId(),
                    attraction.getName(),
                    attractionAnswerComposer.composeSummary(attraction));
        }
        return CreateAttractionQuestionResponse.answered(
                classification.intent(),
                attraction.getId(),
                attraction.getName(),
                attractionAnswerComposer.compose(attraction, classification.intent()));
    }
}
