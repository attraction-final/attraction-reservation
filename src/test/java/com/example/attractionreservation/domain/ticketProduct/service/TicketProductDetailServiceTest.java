package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.domain.ticketProduct.dto.SearchTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketProductDetailServiceTest {

    @Mock
    private TicketProductRepository ticketProductRepository;

    @InjectMocks
    private TicketProductService ticketProductService;

    @ParameterizedTest
    @EnumSource(
            value = TicketProductStatus.class,
            names = {"ON_SALE", "STOPPED", "ENDED"}
    )
    @DisplayName("삭제되지 않은 상품은 판매 상태와 관계없이 상세 조회할 수 있다")
    void getTicketProductById_ExistingProduct_Success(
            TicketProductStatus status
    ) {
        // given
        Long ticketProductId = 1L;
        TicketProduct product = createProduct(ticketProductId, status);

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(product));

        // when
        SearchTicketProductResponse response =
                ticketProductService.findTicketProductById(ticketProductId);

        // then
        assertThat(response).isEqualTo(
                new SearchTicketProductResponse(
                        ticketProductId,
                        "종일이용권",
                        TicketType.ALL_DAY,
                        50000,
                        "하루 동안 이용 가능한 입장권",
                        status
                )
        );

        verify(ticketProductRepository).findById(ticketProductId);
    }

    @Test
    @DisplayName("삭제된 상품도 상세 조회할 수 있으며 DELETED 상태를 반환한다")
    void getTicketProductById_DeletedProduct_Success() {
        // given
        Long ticketProductId = 2L;
        TicketProduct product = createProduct(
                ticketProductId,
                TicketProductStatus.ON_SALE
        );
        product.delete();

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(product));

        // when
        SearchTicketProductResponse response =
                ticketProductService.findTicketProductById(ticketProductId);

        // then
        assertThat(response).isEqualTo(
                new SearchTicketProductResponse(
                        ticketProductId,
                        "종일이용권",
                        TicketType.ALL_DAY,
                        50000,
                        "하루 동안 이용 가능한 입장권",
                        TicketProductStatus.DELETED
                )
        );
        assertThat(product.getStatus())
                .isEqualTo(TicketProductStatus.DELETED);

        verify(ticketProductRepository).findById(ticketProductId);
    }

    @Test
    @DisplayName("존재하지 않는 상품을 상세 조회하면 예외가 발생한다")
    void getTicketProductById_NotFound_Fail() {
        // given
        Long ticketProductId = 999L;

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                ticketProductService.findTicketProductById(ticketProductId)
        )
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.TICKET_PRODUCT_NOT_FOUND)
                );

        verify(ticketProductRepository).findById(ticketProductId);
    }

    private TicketProduct createProduct(
            Long id,
            TicketProductStatus status
    ) {
        TicketProduct product = new TicketProduct(
                "종일이용권",
                50000,
                "하루 동안 이용 가능한 입장권",
                TicketType.ALL_DAY,
                100,
                status
        );
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}