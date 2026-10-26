package com.example.attractionreservation.domain.user.controller;

import com.example.attractionreservation.domain.user.dto.SearchUserCondition;
import com.example.attractionreservation.domain.user.dto.SearchUserListResponse;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<SearchUserListResponse>> getUserAll(
            @Valid @ModelAttribute SearchUserCondition condition
    ) {
        SearchUserListResponse response =
                userService.findUserAll(condition);

        return ResponseEntity.ok(
                ApiResponse.ok("회원 목록 조회에 성공했습니다.", response)
        );
    }
}