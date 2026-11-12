package com.example.attractionreservation.domain.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.nio.charset.StandardCharsets;

public record UpdatePasswordRequest(

        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{8,20}$",
                message = "새 비밀번호는 8~20자이며 영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다."
        )
        String newPassword
) {

    // BCrypt의 비밀번호 입력 길이 제한을 초과하지 않도록 검증한다.
    @AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
    public boolean isPasswordWithinByteLimit() {
        return isWithinByteLimit(currentPassword)
                && isWithinByteLimit(newPassword);
    }

    private static boolean isWithinByteLimit(String password) {
        return password == null
                || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}