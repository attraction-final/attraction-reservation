package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.dto.SearchTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketProductDetailControllerTest {

    @Mock
    private TicketProductService ticketProductService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new TicketProductController(ticketProductService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("상품 상세 조회에 성공하면 200과 상품 정보를 반환한다")
    void getTicketProductById_ExistingProduct_Success() throws Exception {
        // given
        Long ticketProductId = 1L;
        SearchTicketProductResponse response =
                createResponse(ticketProductId, TicketProductStatus.ON_SALE);

        when(ticketProductService.findTicketProductById(ticketProductId))
                .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("티켓 상품 상세 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.ticketProductId").value(1))
                .andExpect(jsonPath("$.data.name").value("종일이용권"))
                .andExpect(jsonPath("$.data.type").value("ALL_DAY"))
                .andExpect(jsonPath("$.data.price").value(50000))
                .andExpect(jsonPath("$.data.description")
                        .value("하루 동안 이용 가능한 입장권"))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"));

        verify(ticketProductService).findTicketProductById(ticketProductId);
    }

    @Test
    @DisplayName("삭제된 상품의 상세 조회도 200과 DELETED 상태를 반환한다")
    void getTicketProductById_DeletedProduct_Success() throws Exception {
        // given
        Long ticketProductId = 2L;
        SearchTicketProductResponse response =
                createResponse(ticketProductId, TicketProductStatus.DELETED);

        when(ticketProductService.findTicketProductById(ticketProductId))
                .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticketProductId").value(2))
                .andExpect(jsonPath("$.data.status").value("DELETED"));

        verify(ticketProductService).findTicketProductById(ticketProductId);
    }

    @Test
    @DisplayName("존재하지 않는 상품의 상세 조회는 404를 반환한다")
    void getTicketProductById_NotFound_Fail() throws Exception {
        // given
        Long ticketProductId = 999L;

        when(ticketProductService.findTicketProductById(ticketProductId))
                .thenThrow(
                        new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND)
                );

        // when & then
        mockMvc.perform(get("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.TICKET_PRODUCT_NOT_FOUND.getMessage()));

        verify(ticketProductService).findTicketProductById(ticketProductId);
    }

    @Test
    @DisplayName("상품 ID가 숫자가 아니면 400을 반환하고 Service를 호출하지 않는다")
    void getTicketProductById_InvalidId_Fail() throws Exception {
        // given
        String ticketProductId = "abc";

        // when & then
        mockMvc.perform(get("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_REQUEST.getMessage()));

        verifyNoInteractions(ticketProductService);
    }

    private SearchTicketProductResponse createResponse(
            Long id,
            TicketProductStatus status
    ) {
        return new SearchTicketProductResponse(
                id,
                "종일이용권",
                TicketType.ALL_DAY,
                50000,
                "하루 동안 이용 가능한 입장권",
                status
        );
    }
}