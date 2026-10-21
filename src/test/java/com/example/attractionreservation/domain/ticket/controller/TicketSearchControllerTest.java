package com.example.attractionreservation.domain.ticket.controller;

import com.example.attractionreservation.domain.ticket.dto.SearchTicketCondition;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketResponse;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketSearchControllerTest {

    @Mock
    private TicketService ticketService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TicketController(ticketService))
                .setCustomArgumentResolvers(
                        new PageableHandlerMethodArgumentResolver()
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("조건이 없으면 임시 사용자와 기본 페이징으로 빈 목록을 반환한다")
    void getTicketAll_NoCondition_DefaultPageable() throws Exception {
        // given
        givenEmptyPage();

        // when & then
        mockMvc.perform(get("/api/tickets/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("내 티켓 목록 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));

        ArgumentCaptor<SearchTicketCondition> conditionCaptor =
                ArgumentCaptor.forClass(SearchTicketCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(ticketService).findTicketAll(
                eq(1L), conditionCaptor.capture(), pageableCaptor.capture()
        );

        assertThat(conditionCaptor.getValue())
                .isEqualTo(new SearchTicketCondition(null, null, null));
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(
                Sort.by(Sort.Order.desc("status"), Sort.Order.desc("id"))
        );
    }

    @Test
    @DisplayName("검색 조건과 정렬을 전달하고 티켓 및 페이지 정보를 응답한다")
    void getTicketAll_WithConditions_Success() throws Exception {
        // given
        SearchTicketResponse response = new SearchTicketResponse(
                10L,
                "TKT-test-code",
                "종일이용권",
                TicketType.ALL_DAY,
                LocalDate.of(2026, 10, 25),
                TicketStatus.UNUSED
        );

        when(ticketService.findTicketAll(
                eq(1L),
                any(SearchTicketCondition.class),
                any(Pageable.class)
        )).thenAnswer(invocation ->
                new PageImpl<>(
                        List.of(response),
                        invocation.getArgument(2, Pageable.class),
                        3
                )
        );

        // when & then
        mockMvc.perform(get("/api/tickets/me")
                        .param("name", "종일")
                        .param("type", "ALL_DAY")
                        .param("status", "UNUSED")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].ticketId").value(10))
                .andExpect(jsonPath("$.data.content[0].ticketCode")
                        .value("TKT-test-code"))
                .andExpect(jsonPath("$.data.content[0].name")
                        .value("종일이용권"))
                .andExpect(jsonPath("$.data.content[0].type")
                        .value("ALL_DAY"))
                .andExpect(jsonPath("$.data.content[0].validDate")
                        .value("2026-10-25"))
                .andExpect(jsonPath("$.data.content[0].status")
                        .value("UNUSED"))
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        ArgumentCaptor<SearchTicketCondition> conditionCaptor =
                ArgumentCaptor.forClass(SearchTicketCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(ticketService).findTicketAll(
                eq(1L), conditionCaptor.capture(), pageableCaptor.capture()
        );

        assertThat(conditionCaptor.getValue()).isEqualTo(
                new SearchTicketCondition(
                        "종일", TicketType.ALL_DAY, TicketStatus.UNUSED
                )
        );
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageableCaptor.getValue().getSort()).isEqualTo(
                Sort.by(Sort.Order.asc("name"), Sort.Order.desc("id"))
        );
    }

    @Test
    @DisplayName("ID 정렬을 직접 지정하면 추가 정렬 없이 방향을 유지한다")
    void getTicketAll_IdSort_PreservesRequestedSort() throws Exception {
        // given
        givenEmptyPage();

        // when & then
        mockMvc.perform(get("/api/tickets/me")
                        .param("sort", "id,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(ticketService).findTicketAll(
                eq(1L),
                any(SearchTicketCondition.class),
                pageableCaptor.capture()
        );

        assertThat(pageableCaptor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "status"})
    @DisplayName("유효하지 않은 종류 또는 상태는 400을 반환한다")
    void getTicketAll_InvalidEnum_BadRequest(String parameter) throws Exception {
        // when & then
        mockMvc.perform(get("/api/tickets/me")
                        .param(parameter, "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(ticketService);
    }

    private void givenEmptyPage() {
        when(ticketService.findTicketAll(
                eq(1L),
                any(SearchTicketCondition.class),
                any(Pageable.class)
        )).thenAnswer(invocation ->
                Page.<SearchTicketResponse>empty(
                        invocation.getArgument(2, Pageable.class)
                )
        );
    }
}