package com.example.attractionreservation.domain.attraction.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SearchAttractionCondition(

        @Min(0)
        Integer page,

        // 한 페이지에 조회할 데이터 개수
        @Min(1)
        @Max(50)
        Integer size,

        @Size(max = 50)
        String keyword,

        // 정렬 기준: NAME(이름순), LATEST(최신순)만 허용
        // @Pattern: 검증 규칙 선언 어노테이션
        @Pattern(regexp = "NAME|LATEST") // (정규표현식 지정 = NAME 또는 LATEST 중 하나만 허용)
        String sort,

        Boolean reservable,

        Boolean operating
) {

    // record의 기본 생성자에서 검색 조건의 기본값을 설정
    public SearchAttractionCondition {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 10;
        }
        if (keyword != null && keyword.isBlank()) {
            keyword = null; // 공백 검색어는 "검색 안 함"으로 처리
        }
        // 정렬 기준이 전달되지 않으면 이름순(NAME)으로 설정
        if (sort == null) {
            sort = "NAME";
        }
        if (reservable == null) {
            reservable = false;
        }
        if (operating == null) {
            operating = false;
        }
    }
}