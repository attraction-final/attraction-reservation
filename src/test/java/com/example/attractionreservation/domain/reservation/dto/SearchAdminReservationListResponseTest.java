package com.example.attractionreservation.domain.reservation.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchAdminReservationListResponseTest {

    @Test
    @DisplayName("관리자 예약 목록과 페이지 정보를 응답에 반영한다")
    void from_success() {
        // given
        SearchAdminReservationResponse item =
                mock(SearchAdminReservationResponse.class);

        Page<SearchAdminReservationResponse> page = new PageImpl<>(
                List.of(item),
                PageRequest.of(1, 2),
                3
        );

        // when
        SearchAdminReservationListResponse response =
                SearchAdminReservationListResponse.from(page);

        // then
        assertThat(response.content()).containsExactly(item);
        assertThat(response.number()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("조회 결과가 없으면 빈 목록과 전체 건수 0을 반환한다")
    void from_emptyPage() {
        // given
        Page<SearchAdminReservationResponse> page =
                Page.empty(PageRequest.of(0, 10));

        // when
        SearchAdminReservationListResponse response =
                SearchAdminReservationListResponse.from(page);

        // then
        assertThat(response.content()).isEmpty();
        assertThat(response.number()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }
}