package com.example.attractionreservation.domain.user.controller;

import com.example.attractionreservation.domain.user.dto.UpdateUserRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserResponse;
import org.springframework.web.bind.annotation.PatchMapping;
import com.example.attractionreservation.domain.user.dto.GetUserResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.example.attractionreservation.domain.user.facade.UserWithdrawalFacade;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.time.Duration;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final UserWithdrawalFacade userWithdrawalFacade;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<CreateUserResponse>> signup(
            @Valid @RequestBody CreateUserRequest request
    ) {
        CreateUserResponse response = userService.signup(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("회원가입이 완료되었습니다.", response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<GetUserResponse>> getMe(
            @AuthenticationPrincipal Long userId
    ) {
        GetUserResponse response = userService.getMe(userId);

        return ResponseEntity.ok(
                ApiResponse.ok("회원 정보 조회에 성공했습니다.", response)
        );
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UpdateUserResponse>> updateMe(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        UpdateUserResponse response = userService.updateMe(userId, request);

        return ResponseEntity.ok(
                ApiResponse.ok("회원 정보 수정에 성공했습니다.", response)
        );
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @AuthenticationPrincipal Long userId
    ) {
        // 요청값으로 회원 ID를 받지 않고 인증된 본인만 탈퇴 처리한다.
        userWithdrawalFacade.withdraw(userId);

        // 서버에서 토큰을 폐기한 뒤 브라우저의 Refresh Token 쿠키도 삭제한다.
        ResponseCookie expiredCookie = ResponseCookie
                .from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        expiredCookie.toString()
                )
                .body(ApiResponse.<Void>ok(
                        "회원 탈퇴에 성공했습니다.",
                        null
                ));
    }
}