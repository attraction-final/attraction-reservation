package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TicketProductRepository ticketProductRepository;

    @AfterEach
    void tearDown() {
        ticketProductRepository.deleteAll();
    }

    @Test
    @DisplayName("정상적인 요청으로 입장권 상품을 등록한다")
    void createTicketProduct_success() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "종일이용권",
                "type", "ALL_DAY",
                "price", 50000,
                "description", "하루 종일 이용 가능한 입장권",
                "saleStatus", "ON_SALE",
                "stock", 100
        );

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("종일이용권"))
                .andExpect(jsonPath("$.data.type").value("ALL_DAY"))
                .andExpect(jsonPath("$.data.price").value(50000))
                .andExpect(jsonPath("$.data.description").value("하루 종일 이용 가능한 입장권"))
                .andExpect(jsonPath("$.data.saleStatus").value("ON_SALE"))
                .andExpect(jsonPath("$.data.stock").value(100))
                .andExpect(jsonPath("$.data.createdAt").exists());

        // DB 저장 검증
        assertThat(ticketProductRepository.count()).isEqualTo(1);

        TicketProduct savedProduct = ticketProductRepository.findAll().get(0);

        assertThat(savedProduct.getName()).isEqualTo("종일이용권");
        assertThat(savedProduct.getPrice()).isEqualTo(50000);
        assertThat(savedProduct.getStock()).isEqualTo(100);
        assertThat(savedProduct.getType()).isEqualTo(TicketType.ALL_DAY);
        assertThat(savedProduct.getStatus()).isEqualTo(TicketProductStatus.ON_SALE);
        assertThat(savedProduct.getDescription()).isEqualTo("하루 종일 이용 가능한 입장권");
    }

    @Test
    @DisplayName("가격이 0이어도 티켓 상품을 등록할 수 있다")
    void createTicketProduct_zeroPrice() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "무료이용권",
                "type", "ALL_DAY",
                "price", 0,
                "description", "무료 입장권",
                "saleStatus", "ON_SALE",
                "stock", 100
        );

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.price").value(0));

        assertThat(ticketProductRepository.count()).isEqualTo(1);

        TicketProduct savedProduct = ticketProductRepository.findAll().get(0);
        assertThat(savedProduct.getPrice()).isZero();
    }

    @Test
    @DisplayName("재고가 0이어도 티켓 상품을 등록할 수 있다")
    void createTicketProduct_zeroStock() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "종일이용권",
                "type", "ALL_DAY",
                "price", 50000,
                "description", "하루 종일 이용 가능한 입장권",
                "saleStatus", "ON_SALE",
                "stock", 0
        );

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.stock").value(0));

        assertThat(ticketProductRepository.count()).isEqualTo(1);

        TicketProduct savedProduct = ticketProductRepository.findAll().get(0);
        assertThat(savedProduct.getStock()).isZero();
    }

    @Test
    @DisplayName("가격이 음수이면 상품 등록에 실패한다")
    void createTicketProduct_negativePrice() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "종일이용권",
                "type", "ALL_DAY",
                "price", -1000,
                "description", "하루 종일 이용 가능한 입장권",
                "saleStatus", "ON_SALE",
                "stock", 100
        );

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.price").exists());

        assertThat(ticketProductRepository.count()).isZero();
    }

    @Test
    @DisplayName("재고가 음수이면 상품 등록에 실패한다")
    void createTicketProduct_negativeStock() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "종일이용권",
                "type", "ALL_DAY",
                "price", 50000,
                "description", "하루 종일 이용 가능한 입장권",
                "saleStatus", "ON_SALE",
                "stock", -1
        );

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.stock").exists());

        assertThat(ticketProductRepository.count()).isZero();
    }

    @Test
    @DisplayName("필수값인 상품 이름이 누락되면 등록에 실패한다")
    void createTicketProduct_missingName() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "type", "ALL_DAY",
                "price", 50000,
                "description", "하루 종일 이용 가능한 입장권",
                "saleStatus", "ON_SALE",
                "stock", 100
        );

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.name").exists());

        assertThat(ticketProductRepository.count()).isZero();
    }

    @Test
    @DisplayName("존재하지 않는 입장권 종류로 요청하면 등록에 실패한다")
    void createTicketProduct_invalidType() throws Exception {
        // given
        Map<String, Object> request = Map.of(
                "name", "종일이용권",
                "type", "INVALID_TYPE",
                "price", 50000,
                "description", "하루 종일 이용 가능한 입장권",
                "saleStatus", "ON_SALE",
                "stock", 100
        );

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("요청 본문 형식이 올바르지 않습니다."));

        assertThat(ticketProductRepository.count()).isZero();
    }
}