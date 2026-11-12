package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationCondition;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminReservationSearchControllerTest {

    @Mock
    private ReservationService reservationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminReservationController(reservationService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("조건이 없으면 기본 페이징과 보조 정렬을 전달하고 빈 목록을 반환한다")
    void getAdminReservationAll_NoCondition_DefaultPageable() throws Exception {
        // given
        givenEmptyPage();

        // when & then
        mockMvc.perform(get("/api/admin/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("예약 목록 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));

        ArgumentCaptor<SearchAdminReservationCondition> conditionCaptor =
                ArgumentCaptor.forClass(SearchAdminReservationCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reservationService).findAdminReservationAll(
                conditionCaptor.capture(), pageableCaptor.capture()
        );
        assertThat(conditionCaptor.getValue())
                .isEqualTo(new SearchAdminReservationCondition(null, null, null, null));
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageableCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("status"), Sort.Order.desc("id")));
    }

    @Test
    @DisplayName("검색 조건과 페이징을 전달하고 예약 목록 및 페이지 정보를 반환한다")
    void getAdminReservationAll_WithConditions_ReturnsReservations() throws Exception {
        // given
        LocalDate date = LocalDate.of(2026, 10, 25);
        SearchAdminReservationResponse response = new SearchAdminReservationResponse(
                10L, "RSV-test-number", 100L, 20L, "롤러코스터", 30L,
                LocalDateTime.of(2026, 10, 25, 16, 0),
                LocalDateTime.of(2026, 10, 25, 16, 10),
                ReservationStatus.RESERVED,
                Instant.parse("2026-10-25T05:00:00Z")
        );
        when(reservationService.findAdminReservationAll(
                any(SearchAdminReservationCondition.class), any(Pageable.class)
        )).thenAnswer(invocation -> new PageImpl<>(
                List.of(response), invocation.getArgument(1, Pageable.class), 3
        ));

        // when & then
        mockMvc.perform(get("/api/admin/reservations")
                        .param("userId", "100")
                        .param("attractionId", "20")
                        .param("status", "RESERVED")
                        .param("date", date.toString())
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "status,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].reservationId").value(10))
                .andExpect(jsonPath("$.data.content[0].reservationNumber").value("RSV-test-number"))
                .andExpect(jsonPath("$.data.content[0].userId").value(100))
                .andExpect(jsonPath("$.data.content[0].attractionId").value(20))
                .andExpect(jsonPath("$.data.content[0].attractionName").value("롤러코스터"))
                .andExpect(jsonPath("$.data.content[0].slotId").value(30))
                .andExpect(jsonPath("$.data.content[0].status").value("RESERVED"))
                .andExpect(jsonPath("$.data.content[0].startsAt").exists())
                .andExpect(jsonPath("$.data.content[0].endsAt").exists())
                .andExpect(jsonPath("$.data.content[0].createdAt").exists())
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        ArgumentCaptor<SearchAdminReservationCondition> conditionCaptor =
                ArgumentCaptor.forClass(SearchAdminReservationCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reservationService).findAdminReservationAll(
                conditionCaptor.capture(), pageableCaptor.capture()
        );
        assertThat(conditionCaptor.getValue()).isEqualTo(
                new SearchAdminReservationCondition(100L, 20L, ReservationStatus.RESERVED, date)
        );
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.asc("status"), Sort.Order.desc("id")));
    }

    @Test
    @DisplayName("ID 정렬을 직접 지정하면 보조 정렬을 추가하지 않고 요청 방향을 유지한다")
    void getAdminReservationAll_IdSort_PreservesRequestedSort() throws Exception {
        // given
        givenEmptyPage();

        // when & then
        mockMvc.perform(get("/api/admin/reservations").param("sort", "id,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reservationService).findAdminReservationAll(
                any(SearchAdminReservationCondition.class), pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    private void givenEmptyPage() {
        when(reservationService.findAdminReservationAll(
                any(SearchAdminReservationCondition.class), any(Pageable.class)
        )).thenAnswer(invocation -> Page.<SearchAdminReservationResponse>empty(
                invocation.getArgument(1, Pageable.class)
        ));
    }
}
