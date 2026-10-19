package com.example.attractionreservation.domain.payment.controller;

import com.example.attractionreservation.domain.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {
    public final PaymentService paymentService;
}
