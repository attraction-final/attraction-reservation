# 예외 처리 및 공통 응답 구현 예시

아래 코드는 원본 컨벤션에서 추린 참고 예시다. 적용 전 실제 프로젝트의 클래스명, 생성자, Lombok 설정, 패키지를 확인한다.

## ErrorCode.java

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    RESERVATION_NOT_FOUND(
        HttpStatus.NOT_FOUND,
        "예약 정보를 찾을 수 없습니다."
    ),
    RESERVATION_CAPACITY_EXCEEDED(
        HttpStatus.CONFLICT,
        "예약 정원이 초과되었습니다."
    );

    private final HttpStatus status;
    private final String message;
}
```

## BusinessException.java

```java
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
```

## GlobalExceptionHandler.java

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(
            BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity
            .status(errorCode.getStatus())
            .body(ApiResponse.error(errorCode.getMessage()));
    }
}
```

## ApiResponse.java

```java
@Getter
@AllArgsConstructor
public class ApiResponse<T> {
    private final boolean success;
    private final String message;
    private final T data;

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(true, message, null);
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
```

## Controller 사용 예시

```java
@GetMapping("/{id}")
public ResponseEntity<ApiResponse<ReservationResponse>> getReservation(
        @PathVariable Long id) {
    ReservationResponse response = reservationService.getReservation(id);
    return ResponseEntity.ok(
        ApiResponse.success("예약 조회에 성공했습니다.", response)
    );
}
```

## 적용 전 확인
- 실제 `ErrorCode` 생성자와 Lombok 어노테이션이 코드와 일치하는지 확인한다.
- 오류 응답에 내부 예외 정보나 스택 트레이스를 노출하지 않는다.
