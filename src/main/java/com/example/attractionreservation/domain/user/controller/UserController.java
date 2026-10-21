package com.example.attractionreservation.domain.user.controller;

import com.example.attractionreservation.global.response.ApiResponse;
import com.example.attractionreservation.domain.user.dto.CreateUserRequest;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<CreateUserResponse>> signup(
            @Valid @RequestBody CreateUserRequest request
    ) {
        CreateUserResponse response = userService.signup(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("회원가입이 완료되었습니다.", response));
    }
}