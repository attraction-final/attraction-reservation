package com.example.attractionreservation.domain.ticketProduct.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

@Getter
@RequiredArgsConstructor
public enum TicketType {
    ALL_DAY(LocalTime.of(10, 0)),  // 종일권 (오전 10시 ~ 오후 10시)
    AFTERNOON(LocalTime.of(15, 0));  // 오후권 (오후 3시 ~ 오후 10시)

    private final LocalTime availableFrom;
}
