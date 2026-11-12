package com.example.attractionreservation.global.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageableDtoTest {

    @Test
    @DisplayName("다음 페이지가 있으면 페이지 정보와 hasNext=true로 변환된다.")
    void from_hasNextPage_ConvertsPageInfo() {
        //given
        Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5);

        //when
        PageableDto<String> result = PageableDto.from(page);

        //then
        assertEquals(List.of("a", "b"), result.content());
        assertEquals(0, result.page());
        assertEquals(2, result.size());
        assertEquals(5, result.totalElements());
        assertEquals(3, result.totalPages());
        assertTrue(result.hasNext());
    }

    @Test
    @DisplayName("마지막 페이지면 hasNext=false로 변환된다.")
    void from_lastPage_HasNextFalse() {
        //given
        Page<String> page = new PageImpl<>(List.of("e"), PageRequest.of(2, 2), 5);

        //when
        PageableDto<String> result = PageableDto.from(page);

        //then
        assertEquals(2, result.page());
        assertFalse(result.hasNext());
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록과 전체 개수 0으로 변환된다.")
    void from_emptyPage_ReturnsEmptyContent() {
        //given
        Page<String> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        //when
        PageableDto<String> result = PageableDto.from(page);

        //then
        assertTrue(result.content().isEmpty());
        assertEquals(0, result.totalElements());
        assertEquals(0, result.totalPages());
        assertFalse(result.hasNext());
    }
}
