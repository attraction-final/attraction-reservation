package com.example.attractionreservation.domain.ticketProduct.entity;

import com.example.attractionreservation.domain.ticket.entity.TicketType;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
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
}