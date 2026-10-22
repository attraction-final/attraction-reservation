
package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketProductDeleteControllerTest {

    private final TicketProductService ticketProductService =
            mock(TicketProductService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TicketProductController(ticketProductService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("티켓 상품 삭제 요청 시 200 OK와 성공 메시지를 반환한다")
    void deleteTicketProduct_ValidId_Success() throws Exception {
        // given
        Long ticketProductId = 1L;

        doNothing().when(ticketProductService).deleteTicketProduct(ticketProductId);

        // when & then
        mockMvc.perform(delete("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("티켓 상품이 삭제되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(ticketProductService).deleteTicketProduct(ticketProductId);
        verifyNoMoreInteractions(ticketProductService);
    }

    @Test
    @DisplayName("존재하지 않는 티켓 상품 삭제 요청 시 404를 반환한다")
    void deleteTicketProduct_NotFound_Fail() throws Exception {
        // given
        Long ticketProductId = 999L;

        doThrow(new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND))
                .when(ticketProductService).deleteTicketProduct(ticketProductId);

        // when & then
        mockMvc.perform(delete("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.TICKET_PRODUCT_NOT_FOUND.getMessage()));

        verify(ticketProductService).deleteTicketProduct(ticketProductId);
        verifyNoMoreInteractions(ticketProductService);
    }

    @Test
    @DisplayName("상품 ID가 숫자가 아니면 400을 반환한다")
    void deleteTicketProduct_InvalidId_Fail() throws Exception {
        // given
        String ticketProductId = "abc";

        // when & then
        mockMvc.perform(delete("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_REQUEST.getMessage()));

        verifyNoInteractions(ticketProductService);
    }
}
