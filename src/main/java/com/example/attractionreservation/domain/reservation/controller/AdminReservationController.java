package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/reservations")
public class AdminReservationController {

    private final ReservationService reservationService;
}
