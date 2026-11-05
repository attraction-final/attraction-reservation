package com.example.attractionreservation.domain.attraction.entity.enums;
//어트랙션 Q&A에서 jev가 고르는 질문 의도
//의사결정 -> 지원 범위를 이넘으로 닫아 둔다. jev는 이 안에서만 고르므로 질문에 지시문을 넣어도
//범위 밖 동작을 할 수 없다
public enum QuestionIntent {
    HEIGHT_LIMIT,
    OPERATING_HOURS,
    OPERATION_STATUS,
    OTHER
}