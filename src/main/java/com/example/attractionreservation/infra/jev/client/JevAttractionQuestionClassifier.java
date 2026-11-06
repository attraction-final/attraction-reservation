package com.example.attractionreservation.infra.jev.client;

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.port.AttractionQuestionClassifier;
import com.example.attractionreservation.domain.attraction.port.dto.QuestionClassification;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
// 구현체가 없으면 서비스 빈을 만들지 못해 앱이 가동되지 않는다.
//pr을 나눠도 앱이 뜨도록 먼저 뼈대만 두고, 연동 전에는 500대신 재시도 안내 503로 응답한다.
//나중에  typesafeClient 연동으로 구현을 채우기
@Component
public class JevAttractionQuestionClassifier implements AttractionQuestionClassifier {

    @Override
    public QuestionClassification classify(String question, List<AttractionCandidate> candidates) {
        throw new BusinessException(ErrorCode.ATTRACTION_QUESTION_UNAVAILABLE);
    }
}