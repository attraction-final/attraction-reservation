package com.example.attractionreservation.domain.user.controller;

import com.example.attractionreservation.domain.user.dto.UpdateUserStatusRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.example.attractionreservation.domain.user.dto.SearchUserResponse;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<SearchUserResponse>> getUserById(
            @PathVariable Long userId
    ) {
        SearchUserResponse response = userService.findUserById(userId);

        return ResponseEntity.ok(
                ApiResponse.ok("회원 상세 조회에 성공했습니다.", response)
        );
    }
    @PatchMapping("/{userId}/status")
    public ResponseEntity<ApiResponse<UpdateUserStatusResponse>> updateUserStatus(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        UpdateUserStatusResponse response =
                userService.updateUserStatus(userId, request);

        return ResponseEntity.ok(
                ApiResponse.ok("회원 상태 변경에 성공했습니다.", response)
        );
    }
}