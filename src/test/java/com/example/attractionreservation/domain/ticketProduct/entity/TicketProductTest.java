
package com.example.attractionreservation.domain.ticketProduct.entity;

import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketProductTest {

    private static final String NAME = "종일이용권";
    private static final int PRICE = 50000;
    private static final String DESCRIPTION = "하루 종일 이용 가능한 입장권";
    private static final TicketType TYPE = TicketType.ALL_DAY;
    private static final int STOCK = 100;
    private static final TicketProductStatus STATUS = TicketProductStatus.ON_SALE;

    @Test
    @DisplayName("정상적인 값으로 티켓 상품을 생성할 수 있다")
    void createTicketProduct_success() {
        // when
        TicketProduct ticketProduct = createTicketProduct(
                NAME, PRICE, DESCRIPTION, TYPE, STOCK, STATUS
        );

        // then
        assertThat(ticketProduct.getName()).isEqualTo(NAME);
        assertThat(ticketProduct.getPrice()).isEqualTo(PRICE);
        assertThat(ticketProduct.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(ticketProduct.getType()).isEqualTo(TYPE);
        assertThat(ticketProduct.getStock()).isEqualTo(STOCK);
        assertThat(ticketProduct.getStatus()).isEqualTo(STATUS);
    }

    @Test
    @DisplayName("상품명이 null이면 예외가 발생한다")
    void createTicketProduct_nullName() {
        assertThatThrownBy(() -> createTicketProduct(
                null, PRICE, DESCRIPTION, TYPE, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품명이 빈 문자열이면 예외가 발생한다")
    void createTicketProduct_emptyName() {
        assertThatThrownBy(() -> createTicketProduct(
                "", PRICE, DESCRIPTION, TYPE, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품명이 공백으로만 이루어지면 예외가 발생한다")
    void createTicketProduct_blankName() {
        assertThatThrownBy(() -> createTicketProduct(
                "   ", PRICE, DESCRIPTION, TYPE, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품 설명이 null이면 예외가 발생한다")
    void createTicketProduct_nullDescription() {
        assertThatThrownBy(() -> createTicketProduct(
                NAME, PRICE, null, TYPE, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품 설명이 빈 문자열이면 예외가 발생한다")
    void createTicketProduct_emptyDescription() {
        assertThatThrownBy(() -> createTicketProduct(
                NAME, PRICE, "", TYPE, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품 설명이 공백으로만 이루어지면 예외가 발생한다")
    void createTicketProduct_blankDescription() {
        assertThatThrownBy(() -> createTicketProduct(
                NAME, PRICE, "   ", TYPE, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("티켓 종류가 null이면 예외가 발생한다")
    void createTicketProduct_nullType() {
        assertThatThrownBy(() -> createTicketProduct(
                NAME, PRICE, DESCRIPTION, null, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("판매 상태가 null이면 예외가 발생한다")
    void createTicketProduct_nullStatus() {
        assertThatThrownBy(() -> createTicketProduct(
                NAME, PRICE, DESCRIPTION, TYPE, STOCK, null
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("가격이 음수이면 예외가 발생한다")
    void createTicketProduct_negativePrice() {
        assertThatThrownBy(() -> createTicketProduct(
                NAME, -1, DESCRIPTION, TYPE, STOCK, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("재고가 음수이면 예외가 발생한다")
    void createTicketProduct_negativeStock() {
        assertThatThrownBy(() -> createTicketProduct(
                NAME, PRICE, DESCRIPTION, TYPE, -1, STATUS
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("가격이 0이어도 티켓 상품을 생성할 수 있다")
    void createTicketProduct_zeroPrice() {
        TicketProduct ticketProduct = createTicketProduct(
                NAME, 0, DESCRIPTION, TYPE, STOCK, STATUS
        );

        assertThat(ticketProduct.getPrice()).isZero();
    }

    @Test
    @DisplayName("재고가 0이어도 티켓 상품을 생성할 수 있다")
    void createTicketProduct_zeroStock() {
        TicketProduct ticketProduct = createTicketProduct(
                NAME, PRICE, DESCRIPTION, TYPE, 0, STATUS
        );

        assertThat(ticketProduct.getStock()).isZero();
    }

    @Test
    @DisplayName("삭제된 상품은 수정할 수 없으며 기존 값이 유지된다")
    void updateTicketProduct_DeletedProduct_Fail() {
        // given
        TicketProduct ticketProduct = new TicketProduct(
                "종일이용권",
                50000,
                "기존 설명",
                TicketType.ALL_DAY,
                100,
                TicketProductStatus.DELETED
        );

        // when & then
        assertThatThrownBy(() -> ticketProduct.updateTicketProduct(
                "변경된 상품명",
                30000,
                "변경된 설명",
                50,
                TicketProductStatus.ON_SALE
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.TICKET_PRODUCT_ALREADY_DELETED)
                );

        assertThat(ticketProduct.getName()).isEqualTo("종일이용권");
        assertThat(ticketProduct.getPrice()).isEqualTo(50000);
        assertThat(ticketProduct.getDescription()).isEqualTo("기존 설명");
        assertThat(ticketProduct.getStock()).isEqualTo(100);
        assertThat(ticketProduct.getStatus())
                .isEqualTo(TicketProductStatus.DELETED);
    }

    @Test
    @DisplayName("수정 요청 상태가 DELETED이면 거절하고 기존 값을 유지한다")
    void updateTicketProduct_deletedStatus_rejected() {
        TicketProduct product = new TicketProduct(
                "종일이용권",
                50000,
                "기존 설명",
                TicketType.ALL_DAY,
                100,
                TicketProductStatus.ON_SALE
        );

        assertThatThrownBy(() -> product.updateTicketProduct(
                "변경된 이름",
                60000,
                "변경된 설명",
                50,
                TicketProductStatus.DELETED
        ))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_TICKET_PRODUCT_STATUS)
                );

        assertThat(product.getName()).isEqualTo("종일이용권");
        assertThat(product.getPrice()).isEqualTo(50000);
        assertThat(product.getDescription()).isEqualTo("기존 설명");
        assertThat(product.getStock()).isEqualTo(100);
        assertThat(product.getStatus())
                .isEqualTo(TicketProductStatus.ON_SALE);
    }

    private TicketProduct createTicketProduct(
            String name,
            int price,
            String description,
            TicketType type,
            int stock,
            TicketProductStatus status
    ) {
        return new TicketProduct(
                name, price, description, type, stock, status
        );
    }
}
