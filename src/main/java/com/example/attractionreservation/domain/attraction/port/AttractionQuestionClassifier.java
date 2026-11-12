package com.example.attractionreservation.domain.attraction.port;
//의사결정 -> 포트로분리한다. 분기 규칙을 jevㅇ벗이 테스트할 수 있고, 서비스가 sdk타입을 몰라도 됨.

import com.example.attractionreservation.domain.attraction.dto.AttractionCandidate;
import com.example.attractionreservation.domain.attraction.port.dto.QuestionClassification;

import java.util.List;

public interface AttractionQuestionClassifier {
    //사용자 질문이 어떤 어트랙션의 무엇을 묻는지 분류한다.
    QuestionClassification classify(String question, List<AttractionCandidate> candidates);
}


//보냄 → classify(
//  "모니모러쉬 키제한 있어?",
//  [ {1, 모니모 러쉬}, {2, 허리케인}, {3, 롤링 엑스 트레인}, {4, 범퍼카} ]
//)
//돌려받음 ← QuestionClassification