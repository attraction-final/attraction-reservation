
package com.example.attractionreservation.domain.ticketProduct.service;

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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketProductDeleteServiceTest {

    @Mock
    private TicketProductRepository ticketProductRepository;

    @InjectMocks
    private TicketProductService ticketProductService;

    private TicketProduct ticketProduct;

    @BeforeEach
    void setUp() {
        ticketProduct = createTicketProduct(TicketProductStatus.ON_SALE);
    }

    private TicketProduct createTicketProduct(TicketProductStatus status) {
        return new TicketProduct(
                "종일이용권",
                50000,
                "하루 동안 이용 가능한 입장권",
                TicketType.ALL_DAY,
                100,
                status
        );
    }

    @Test
    @DisplayName("판매 중인 티켓 상품을 삭제하면 DELETED 상태로 변경된다")
    void delete_onSale_success() {
        // given
        Long ticketProductId = 1L;

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(ticketProduct));

        // when
        ticketProductService.delete(ticketProductId);

        // then
        assertThat(ticketProduct.getStatus())
                .isEqualTo(TicketProductStatus.DELETED);

        verify(ticketProductRepository).findById(ticketProductId);
        verify(ticketProductRepository, never()).delete(any(TicketProduct.class));
        verify(ticketProductRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("판매 중지된 티켓 상품도 삭제할 수 있다")
    void delete_stopped_success() {
        // given
        Long ticketProductId = 1L;
        TicketProduct stoppedProduct =
                createTicketProduct(TicketProductStatus.STOPPED);

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(stoppedProduct));

        // when
        ticketProductService.delete(ticketProductId);

        // then
        assertThat(stoppedProduct.getStatus())
                .isEqualTo(TicketProductStatus.DELETED);

        verify(ticketProductRepository, never()).delete(any(TicketProduct.class));
        verify(ticketProductRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("이미 삭제된 상품을 다시 삭제해도 정상 처리된다")
    void delete_alreadyDeleted_success() {
        // given
        Long ticketProductId = 1L;
        TicketProduct deletedProduct =
                createTicketProduct(TicketProductStatus.DELETED);

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.of(deletedProduct));

        // when
        ticketProductService.delete(ticketProductId);

        // then
        assertThat(deletedProduct.getStatus())
                .isEqualTo(TicketProductStatus.DELETED);

        verify(ticketProductRepository).findById(ticketProductId);
        verify(ticketProductRepository, never()).delete(any(TicketProduct.class));
        verify(ticketProductRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("존재하지 않는 티켓 상품을 삭제하면 예외가 발생한다")
    void delete_notFound_fail() {
        // given
        Long ticketProductId = 999L;

        when(ticketProductRepository.findById(ticketProductId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> ticketProductService.delete(ticketProductId))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.TICKET_PRODUCT_NOT_FOUND.getMessage());

        verify(ticketProductRepository).findById(ticketProductId);
        verify(ticketProductRepository, never()).delete(any(TicketProduct.class));
        verify(ticketProductRepository, never()).deleteById(anyLong());
    }
}
