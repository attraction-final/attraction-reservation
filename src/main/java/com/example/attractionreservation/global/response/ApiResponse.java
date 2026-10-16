package com.example.attractionreservation.global.response;

import com.example.attractionreservation.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.Map;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final boolean success;
    private final String message;
    private final T data;

    private ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "요청이 성공적으로 처리되었습니다.", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, "요청이 성공적으로 처리되었습니다.", null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getMessage(), null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, message, null);
    }

    public static ApiResponse<Map<String, String>> validationFail(Map<String, String> fieldErrors) {
        return new ApiResponse<>(false, ErrorCode.INVALID_INPUT_VALUE.getMessage(), fieldErrors);
    }
}