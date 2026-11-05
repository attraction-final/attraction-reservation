package com.example.attractionreservation.domain.attraction.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

//서비스는 아직 골격이다.나중에 시나리오 테스트로 교체한다.
class AttractionQuestionServiceTest {

    private final AttractionQuestionService attractionQuestionService = new AttractionQuestionService();

    @Test
    @DisplayName("아직 구현 전이라 호출하면 UnsupportedOperationException이 난다")
    void answer_notImplemented_throwsException() {
        assertThatThrownBy(() -> attractionQuestionService.answer("모니모러쉬 키제한 있어?"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}