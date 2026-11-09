package com.example.attractionreservation.global.response;

import com.example.attractionreservation.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.Map;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final boolean success;
    // 실패 응답에만 들어가는 ErrorCode 이름 (예: "ATTRACTION_NOT_FOUND"). 성공 응답에서는 빠진다.
    // 클라이언트가 바뀔 수 있는 message 문장 대신 이 값으로 실패 원인을 구분한다.
    private final String code;
    private final String message;
    private final T data;

    private ApiResponse(boolean success, String code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, "요청이 성공적으로 처리되었습니다.", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, null, message, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, "요청이 성공적으로 처리되었습니다.", null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.name(), errorCode.getMessage(), null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.name(), message, null);
    }

    public static ApiResponse<Map<String, String>> validationFail(Map<String, String> fieldErrors) {
        return new ApiResponse<>(false, ErrorCode.INVALID_INPUT_VALUE.name(),
                ErrorCode.INVALID_INPUT_VALUE.getMessage(), fieldErrors);
    }
}
