
package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

        doNothing().when(ticketProductService).delete(ticketProductId);

        // when & then
        mockMvc.perform(delete("/api/ticket-products/{ticketProductId}", ticketProductId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("티켓 상품이 삭제되었습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(ticketProductService).delete(ticketProductId);
        verifyNoMoreInteractions(ticketProductService);
    }
}
