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
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_NAME);
        }

        if (description == null || description.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_DESCRIPTION);
        }

        if (type == null) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_TYPE);
        }

        if (status == null) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_STATUS);
        }

        if (price < 0) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_PRICE);
        }

        if (stock < 0) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_PRODUCT_STOCK);
        }

        this.name = name;
        this.price = price;
        this.description = description;
        this.type = type;
        this.stock = stock;
        this.status = status;
    }

    public void delete() {
        this.status = TicketProductStatus.DELETED;
    }
}
