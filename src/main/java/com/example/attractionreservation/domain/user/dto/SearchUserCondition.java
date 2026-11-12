package com.example.attractionreservation.domain.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SearchUserCondition(
        @NotNull
        @Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.")
        Integer page,

        @NotNull
        @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
        Integer size
) {

    public SearchUserCondition {
        // 생략된 조회 조건에 명세서의 기본값을 적용한다.
        page = (page == null) ? 0 : page;
        size = (size == null) ? 10 : size;
    }
}