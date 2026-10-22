package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    // 예약 생성
    // TODO : 인증 완료 후 사용자 검증
    @PostMapping
    public ResponseEntity<ApiResponse<CreateReservationResponse>> createReservation(
            @Valid @RequestBody CreateReservationRequest request
    ) {
        Long userId = 1L; // 임시 유저 id
        CreateReservationResponse response = reservationService.createReservation(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("어트랙션 예약에 성공했습니다.", response));
    }
}
