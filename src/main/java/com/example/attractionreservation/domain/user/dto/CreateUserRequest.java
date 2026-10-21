package com.example.attractionreservation.domain.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record CreateUserRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 50, message = "이메일은 50자 이하여야 합니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{8,20}$",
                message = "비밀번호는 8~20자이며 영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다."
        )
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 50, message = "닉네임은 50자 이하여야 합니다.")
        String nickname
) {

        @AssertTrue(message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
        public boolean isPasswordWithinByteLimit() {
                return password == null
                        || password.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
}