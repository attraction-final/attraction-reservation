package com.example.attractionreservation.domain.attraction.entity.enums;
//어트랙션 뮤엔에이의 응답 종류, 프론트는 이 값으로 답변, 후보, 선택, 미지원 안내 화면을 나눈다.
public enum QuestionAnswerType {
    ANSWERED,
    NEED_CLARIFICATION,
    UNSUPPORTED
}