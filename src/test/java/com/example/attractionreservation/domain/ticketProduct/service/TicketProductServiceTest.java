
package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketProductServiceTest {

    @Mock
    private TicketProductRepository ticketProductRepository;

    @InjectMocks
    private TicketProductService ticketProductService;

    private TicketProduct ticketProduct;
    private TicketType originalType;
    private TicketProductStatus originalStatus;

    @BeforeEach
    void setUp() {
        originalType = TicketType.values()[0];
        originalStatus = TicketProductStatus.ON_SALE;

        ticketProduct = new TicketProduct(
                "기존 자유이용권",
                50000,
                "기존 상품 설명",
                originalType,
                100,
                originalStatus
        );
    }

    @Test
    @DisplayName("티켓 상품 수정에 성공한다")
    void updateTicketProduct_success() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                60000,
                "변경된 상품 설명",
                TicketProductStatus.STOPPED,
                80
        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when
        UpdateTicketProductResponse response =
                ticketProductService.updateTicketProduct(ticketProductId, request);

        // then
        assertThat(response.name()).isEqualTo("프리미엄 자유이용권");
        assertThat(response.price()).isEqualTo(60000);
        assertThat(response.description()).isEqualTo("변경된 상품 설명");
        assertThat(response.saleStatus()).isEqualTo(TicketProductStatus.STOPPED);
        assertThat(response.stock()).isEqualTo(80);

        assertThat(ticketProduct.getName()).isEqualTo("프리미엄 자유이용권");
        assertThat(ticketProduct.getPrice()).isEqualTo(60000);
        assertThat(ticketProduct.getDescription()).isEqualTo("변경된 상품 설명");
        assertThat(ticketProduct.getStatus()).isEqualTo(TicketProductStatus.STOPPED);
        assertThat(ticketProduct.getStock()).isEqualTo(80);
        assertThat(ticketProduct.getType()).isEqualTo(originalType);

        verify(ticketProductRepository).findById(ticketProductId);
        verify(ticketProductRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 티켓 상품은 수정할 수 없다")
    void updateTicketProduct_notFound() {
        // given
        Long ticketProductId = 999L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                60000,
                "변경된 상품 설명",
                originalStatus,
                80

        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProduct(ticketProductId, request)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.TICKET_PRODUCT_NOT_FOUND);
                });

        verify(ticketProductRepository).findById(ticketProductId);
        verify(ticketProductRepository, never()).save(any());
    }

    @Test
    @DisplayName("상품명이 공백이면 수정에 실패한다")
    void updateTicketProduct_invalidName() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                " ",
                60000,
                "변경된 상품 설명",
                originalStatus,
                80

        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProduct(ticketProductId, request)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_TICKET_PRODUCT_NAME);
                });

        assertThat(ticketProduct.getName()).isEqualTo("기존 자유이용권");
        assertThat(ticketProduct.getPrice()).isEqualTo(50000);
        assertThat(ticketProduct.getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("상품 설명이 공백이면 수정에 실패한다")
    void updateTicketProduct_blankDescription() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                60000,
                "   ",
                originalStatus,
                80
        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProduct(ticketProductId, request)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_TICKET_PRODUCT_DESCRIPTION);
                });

        assertThat(ticketProduct.getName()).isEqualTo("기존 자유이용권");
        assertThat(ticketProduct.getDescription()).isEqualTo("기존 상품 설명");
        assertThat(ticketProduct.getPrice()).isEqualTo(50000);
        assertThat(ticketProduct.getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("상품 설명이 null이면 수정에 실패한다")
    void updateTicketProduct_nullDescription() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                60000,
                null,
                originalStatus,
                80
        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProduct(ticketProductId, request)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_TICKET_PRODUCT_DESCRIPTION);
                });

        assertThat(ticketProduct.getDescription()).isEqualTo("기존 상품 설명");
        assertThat(ticketProduct.getPrice()).isEqualTo(50000);
        assertThat(ticketProduct.getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("판매 상태가 null이면 수정에 실패한다")
    void updateTicketProduct_nullSaleStatus() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                60000,
                "변경된 상품 설명",
                null,
                80
        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProduct(ticketProductId, request)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_TICKET_PRODUCT_STATUS);
                });

        assertThat(ticketProduct.getStatus()).isEqualTo(originalStatus);
        assertThat(ticketProduct.getName()).isEqualTo("기존 자유이용권");
        assertThat(ticketProduct.getPrice()).isEqualTo(50000);
        assertThat(ticketProduct.getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("가격이 음수이면 수정에 실패한다")
    void updateTicketProduct_invalidPrice() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                -1000,
                "변경된 상품 설명",
                originalStatus,
                80

        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProduct(ticketProductId, request)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_TICKET_PRODUCT_PRICE);
                });

        assertThat(ticketProduct.getName()).isEqualTo("기존 자유이용권");
        assertThat(ticketProduct.getPrice()).isEqualTo(50000);
    }

    @Test
    @DisplayName("재고가 음수이면 수정에 실패한다")
    void updateTicketProduct_invalidStock() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                60000,
                "변경된 상품 설명",
                originalStatus,
                -1

        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProduct(ticketProductId, request)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_TICKET_PRODUCT_STOCK);
                });

        assertThat(ticketProduct.getName()).isEqualTo("기존 자유이용권");
        assertThat(ticketProduct.getPrice()).isEqualTo(50000);
        assertThat(ticketProduct.getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("가격이 0원이면 정상적으로 수정된다")
    void updateTicketProduct_zeroPrice() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "무료 자유이용권",
                0,
                "무료 이용권 설명",
                originalStatus,
                80
        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when
        UpdateTicketProductResponse response =
                ticketProductService.updateTicketProduct(ticketProductId, request);

        // then
        assertThat(response.price()).isZero();
        assertThat(ticketProduct.getPrice()).isZero();

        assertThat(response.name()).isEqualTo("무료 자유이용권");
        assertThat(ticketProduct.getName()).isEqualTo("무료 자유이용권");

        verify(ticketProductRepository).findById(ticketProductId);
        verify(ticketProductRepository, never()).save(any());
    }

    @Test
    @DisplayName("재고가 0개이면 정상적으로 수정된다")
    void updateTicketProduct_zeroStock() {
        // given
        Long ticketProductId = 1L;

        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "프리미엄 자유이용권",
                60000,
                "변경된 상품 설명",
                originalStatus,
                0
        );

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when
        UpdateTicketProductResponse response =
                ticketProductService.updateTicketProduct(ticketProductId, request);

        // then
        assertThat(response.stock()).isZero();
        assertThat(ticketProduct.getStock()).isZero();

        assertThat(response.price()).isEqualTo(60000);
        assertThat(ticketProduct.getPrice()).isEqualTo(60000);

        verify(ticketProductRepository).findById(ticketProductId);
        verify(ticketProductRepository, never()).save(any());
    }

    @Test
    @DisplayName("재고를 복구하면 상품별 수량만큼 재고가 늘어난다")
    void updateTicketProductsStockIncrease_existingProducts_increasesStock() {
        // given
        TicketProduct first = createStockProduct(1L, 10, TicketProductStatus.ON_SALE);
        TicketProduct second = createStockProduct(2L, 20, TicketProductStatus.ON_SALE);

        when(ticketProductRepository.findAllWithUpdateByIdInOrderById(Set.of(1L, 2L)))
                .thenReturn(List.of(first, second));

        // when
        ticketProductService.updateTicketProductsStockIncrease(Map.of(1L, 2, 2L, 3));

        // then
        assertThat(first.getStock()).isEqualTo(12);
        assertThat(second.getStock()).isEqualTo(23);
    }

    @Test
    @DisplayName("재고 0으로 품절된 상품을 복구하면 다시 판매 중이 된다")
    void updateTicketProductsStockIncrease_outOfStock_reopensSale() {
        // given
        TicketProduct soldOut = createStockProduct(1L, 0, TicketProductStatus.OUT_OF_STOCK);

        when(ticketProductRepository.findAllWithUpdateByIdInOrderById(Set.of(1L)))
                .thenReturn(List.of(soldOut));

        // when
        ticketProductService.updateTicketProductsStockIncrease(Map.of(1L, 2));

        // then
        assertThat(soldOut.getStock()).isEqualTo(2);
        assertThat(soldOut.getStatus()).isEqualTo(TicketProductStatus.ON_SALE);
    }

    @Test
    @DisplayName("복구할 상품이 하나라도 없으면 예외가 발생하고 재고를 바꾸지 않는다")
    void updateTicketProductsStockIncrease_missingProduct_throwsNotFound() {
        // given: 2개를 요청했지만 1개만 조회된다.
        TicketProduct first = createStockProduct(1L, 10, TicketProductStatus.ON_SALE);

        when(ticketProductRepository.findAllWithUpdateByIdInOrderById(Set.of(1L, 2L)))
                .thenReturn(List.of(first));

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.updateTicketProductsStockIncrease(Map.of(1L, 2, 2L, 3)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.TICKET_PRODUCT_NOT_FOUND)
                );
        assertThat(first.getStock()).isEqualTo(10);
    }

    private TicketProduct createStockProduct(Long id, int stock, TicketProductStatus status) {
        TicketProduct product = new TicketProduct(
                "재고상품", 50000, "재고 테스트 상품", TicketType.ALL_DAY, stock, status
        );
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}
