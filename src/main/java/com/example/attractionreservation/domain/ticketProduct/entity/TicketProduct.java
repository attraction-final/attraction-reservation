package com.example.attractionreservation.domain.ticketProduct.entity;

import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "ticket_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketProduct extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "price", nullable = false)
    private int price;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TicketType type;

    @Column(name = "stock", nullable = false)
    private int stock;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private TicketProductStatus status;

    public TicketProduct(String name, int price, String description, TicketType type, int stock, TicketProductStatus status) {
        validateName(name);
        validateDescription(description);

        if (type == null) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_TYPE);
        }

        validateStatus(status);
        validatePrice(price);
        validateStock(stock);

        this.name = name;
        this.price = price;
        this.description = description;
        this.type = type;
        this.stock = stock;
        this.status = status;
    }

    // 티켓 상품 수정
    public void updateTicketProduct(String name, int price, String description, int stock, TicketProductStatus status) {
        if (this.status == TicketProductStatus.DELETED) {
            throw new BusinessException(ErrorCode.TICKET_PRODUCT_ALREADY_DELETED);
        }

        if (status == TicketProductStatus.DELETED) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_STATUS);
        }

        validateName(name);
        validateDescription(description);
        validateStatus(status);
        validatePrice(price);
        validateStock(stock);

        this.name = name;
        this.price = price;
        this.description = description;
        this.stock = stock;
        this.status = status;
    }

    public void delete() {
        this.status = TicketProductStatus.DELETED;
    }

    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        // 품절 상품은 판매 중지와 구분해 재고 부족으로 응답한다.
        if (this.status == TicketProductStatus.OUT_OF_STOCK) {
            throw new BusinessException(ErrorCode.TICKET_PRODUCT_STOCK_INSUFFICIENT);
        }

        if (this.status != TicketProductStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.ORDER_PRODUCT_NOT_ON_SALE);
        }

        if (this.stock < quantity) {
            throw new BusinessException(ErrorCode.TICKET_PRODUCT_STOCK_INSUFFICIENT);
        }

        this.stock -= quantity;

        if (this.stock == 0) {
            this.status = TicketProductStatus.OUT_OF_STOCK;
        }
    }

    public void increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        if (this.stock == 0 && this.status == TicketProductStatus.OUT_OF_STOCK) {
            this.status = TicketProductStatus.ON_SALE;
        }

        this.stock += quantity;
    }

    // 검증용 중복 코드 메서드로 분리
    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_NAME);
        }
    }
    private void validateDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_DESCRIPTION);
        }
    }
    private void validateStatus(TicketProductStatus status) {
        if (status == null) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_STATUS);
        }
    }

    private void validatePrice(int price) {
        if (price < 0) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_PRICE);
        }
    }

    private void validateStock(int stock) {
        if (stock < 0) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_STOCK);
        }
    }
}
