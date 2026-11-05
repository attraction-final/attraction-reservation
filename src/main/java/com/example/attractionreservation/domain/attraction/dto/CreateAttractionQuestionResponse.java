package com.example.attractionreservation.domain.attraction.dto;

import com.example.attractionreservation.domain.attraction.entity.enums.QuestionAnswerType;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;

import java.util.List;
//어트랙션 Q&A 응답
public record CreateAttractionQuestionResponse (
        QuestionAnswerType type,
        QuestionIntent intent,
        Long attractionId,
        String attractionName,
        String answer,
        List<AttractionCandidate> candidates //되묻기 -> 후보 목록 보여줌
) {
    //정상 답변
    public static CreateAttractionQuestionResponse answered(QuestionIntent intent, Long attractionId, String attractionName, String answer) {
        return new CreateAttractionQuestionResponse(
                QuestionAnswerType.ANSWERED, intent ,attractionId, attractionName, answer, List.of()
        );
    }
    //되묻기
    public static CreateAttractionQuestionResponse needClarification(String answer, List<AttractionCandidate> candidates) {
        return new CreateAttractionQuestionResponse(
                QuestionAnswerType.NEED_CLARIFICATION, null , null, null,answer, candidates);
    }
    //지원 안함
    public  static CreateAttractionQuestionResponse unsupported(String answer) {
        return new CreateAttractionQuestionResponse(
                QuestionAnswerType.UNSUPPORTED, null, null, null, answer, List.of());
    }
}