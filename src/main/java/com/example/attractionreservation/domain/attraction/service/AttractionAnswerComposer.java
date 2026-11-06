package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import org.springframework.stereotype.Component;

//의사결정 -> 문구 조립은 경우의 수가 많아 파라미터 테스트로 따로 검증하려고 서비스에서 분리함
@Component
public class AttractionAnswerComposer {
    public String compose(Attraction attraction, QuestionIntent intent) {
        throw new UnsupportedOperationException("나중에 구현할거");
    }

    public String composeSummary(Attraction attraction) {
        throw new UnsupportedOperationException("나중에 구현할거");
    }
}