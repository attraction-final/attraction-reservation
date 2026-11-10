package com.example.attractionreservation.domain.attraction.port.dto;

import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;

import java.util.Map;

//질문 분류 결과
//jev는 문장을 만들지 않으므로 분류값과 확신도만 담는다. 답변의 숫자는 항상 db값으로 만듬.
public record QuestionClassification(
        Long attractionId, //어트랙션 없음(NONE)으로 분류되면 null
        double attractionConfidence, //어트랙션 분류 확신도 0~1
        Map<Long , Double> attractionProbabilities, //어트랙션 ID별 확률. 없음(NONE)은 포함하지 않는다
        QuestionIntent intent, //가장 유력한 질문 의도
        double intentConfidence //의도 분류 확신도 0~1
) {
}

//어댑터가 Jev 응답을 우리 양식인 QuestionClassification으로 번역해서 서비스에 돌려줌

//QuestionClassification(
//  attractionId = 1,
//  attractionConfidence = 0.9,
//  attractionProbabilities = {1: 0.9, 2: 0.05},
//  intent = HEIGHT_LIMIT,
//  intentConfidence = 0.9
//)