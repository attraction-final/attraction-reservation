package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.dto.SearchTicketProductCondition;
import com.example.attractionreservation.domain.ticketProduct.dto.SearchTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketProductSearchControllerTest {

    @Mock
    private TicketProductService ticketProductService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new TicketProductController(ticketProductService)
                )
                .setCustomArgumentResolvers(
                        new PageableHandlerMethodArgumentResolver()
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("검색 조건이 없으면 기본 페이징과 정렬을 사용한다")
    void getTicketProductAll_NoCondition_DefaultPageable() throws Exception {
        // given
        when(ticketProductService.getTicketProductAll(
                any(SearchTicketProductCondition.class),
                any(Pageable.class)
        )).thenAnswer(invocation ->
                Page.<SearchTicketProductResponse>empty(
                        invocation.getArgument(1, Pageable.class)
                )
        );

        // when & then
        mockMvc.perform(get("/api/ticket-products")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("티켓 상품 목록 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.content").isEmpty());

        ArgumentCaptor<SearchTicketProductCondition> conditionCaptor =
                ArgumentCaptor.forClass(SearchTicketProductCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(ticketProductService).getTicketProductAll(
                conditionCaptor.capture(),
                pageableCaptor.capture()
        );

        assertThat(conditionCaptor.getValue())
                .isEqualTo(new SearchTicketProductCondition(null, null, null));

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "type"));
    }

    @Test
    @DisplayName("검색 조건과 페이징을 전달하고 상품 목록을 반환한다")
    void getTicketProductAll_WithConditions_Success() throws Exception {
        // given
        SearchTicketProductResponse response =
                new SearchTicketProductResponse(
                        10L,
                        "종일이용권",
                        TicketType.ALL_DAY,
                        50000,
                        "테스트 상품 설명",
                        TicketProductStatus.ON_SALE
                );

        when(ticketProductService.getTicketProductAll(
                any(SearchTicketProductCondition.class),
                any(Pageable.class)
        )).thenAnswer(invocation ->
                new PageImpl<>(
                        List.of(response),
                        invocation.getArgument(1, Pageable.class),
                        3
                )
        );

        // when & then
        mockMvc.perform(get("/api/ticket-products")
                        .param("name", "종일")
                        .param("type", "ALL_DAY")
                        .param("status", "ON_SALE")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "name,asc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].ticketProductId")
                        .value(10))
                .andExpect(jsonPath("$.data.content[0].name")
                        .value("종일이용권"))
                .andExpect(jsonPath("$.data.content[0].type")
                        .value("ALL_DAY"))
                .andExpect(jsonPath("$.data.content[0].price")
                        .value(50000))
                .andExpect(jsonPath("$.data.content[0].description")
                        .value("테스트 상품 설명"))
                .andExpect(jsonPath("$.data.content[0].status")
                        .value("ON_SALE"));

        ArgumentCaptor<SearchTicketProductCondition> conditionCaptor =
                ArgumentCaptor.forClass(SearchTicketProductCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(ticketProductService).getTicketProductAll(
                conditionCaptor.capture(),
                pageableCaptor.capture()
        );

        assertThat(conditionCaptor.getValue()).isEqualTo(
                new SearchTicketProductCondition(
                        "종일",
                        TicketType.ALL_DAY,
                        TicketProductStatus.ON_SALE
                )
        );

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(2);
        assertThat(pageable.getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "name"));
    }

    @Test
    @DisplayName("존재하지 않는 티켓 종류로 검색하면 400을 반환한다")
    void getTicketProductAll_InvalidType_BadRequest() throws Exception {
        // when & then
        mockMvc.perform(get("/api/ticket-products")
                        .param("type", "INVALID_TYPE")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(ticketProductService);
    }

    @Test
    @DisplayName("존재하지 않는 판매 상태로 검색하면 400을 반환한다")
    void getTicketProductAll_InvalidStatus_BadRequest() throws Exception {
        // when & then
        mockMvc.perform(get("/api/ticket-products")
                        .param("status", "INVALID_STATUS")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(ticketProductService);
    }
}