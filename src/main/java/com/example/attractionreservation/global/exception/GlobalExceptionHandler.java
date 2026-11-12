package com.example.attractionreservation.global.exception;

import com.example.attractionreservation.global.response.ApiResponse;
import com.example.attractionreservation.global.util.LogValues;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    // 서비스 또는 엔티티의 비즈니스 규칙을 위반한 경우
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            // 서버 쪽 원인으로 사용자 요청이 실패했으므로 알림 대상인 ERROR로 남긴다.
            // 원인과 스택 트레이스는 예외를 바꿔 던진 곳에서 남기므로 여기서는 예외 객체 없이 요청 정보만 남긴다.
            log.error(
                    "event=REQUEST_FAILED userId={} method={} uri=\"{}\" errorCode={} httpStatus={}",
                    getAuthenticatedUserId(),
                    request.getMethod(),
                    LogValues.quoted(request.getRequestURI()),
                    errorCode.name(),
                    errorCode.getStatus().value()
            );
        } else {
            logRejected(request, errorCode);
        }
        // 서비스에서 직접 만든 메시지가 있으면 그 메시지를, 없으면 ErrorCode 기본 메시지를 내보낸다.
        String message = exception.getMessage();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.error(
                     errorCode,
                     message != null ? message : errorCode.getMessage()));
    }

    // @Valid가 적용된 요청 본문의 필드 검증에 실패한 경우
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        logRejected(request, ErrorCode.INVALID_INPUT_VALUE);
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ApiResponse.validationFail(fieldErrors));
    }

    // 컨트롤러 메서드 파라미터 검증에 실패한 경우
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(HttpServletRequest request) {
        logRejected(request, ErrorCode.INVALID_INPUT_VALUE);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE));
    }

    // 요청 파라미터의 타입이 맞지 않는 경우
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(HttpServletRequest request) {
        logRejected(request, ErrorCode.INVALID_REQUEST);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_REQUEST));
    }

    // 존재하지 않는 URL로 요청한 경우
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleUrlNotFound(HttpServletRequest request) {
        logRejected(request, ErrorCode.URL_NOT_FOUND);
        return ResponseEntity.status(ErrorCode.URL_NOT_FOUND.getStatus()).body(ApiResponse.error(ErrorCode.URL_NOT_FOUND));
    }

    // JSON 문법 오류 또는 요청 DTO로 변환할 수 없는 본문을 보낸 경우
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequestBody(HttpServletRequest request) {
        logRejected(request, ErrorCode.INVALID_REQUEST_BODY);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.INVALID_REQUEST_BODY));
    }

    // 필수 쿼리 파라미터를 누락한 경우
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(HttpServletRequest request) {
        logRejected(request, ErrorCode.MISSING_REQUEST_VALUE);
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.MISSING_REQUEST_VALUE));
    }

    // 엔드포인트가 지원하지 않는 HTTP 메서드로 요청한 경우
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpServletRequest request) {
        logRejected(request, ErrorCode.METHOD_NOT_ALLOWED);
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    // 엔드포인트가 처리할 수 없는 Content-Type으로 요청한 경우
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpServletRequest request) {
        logRejected(request, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getStatus())
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    // 동시에 같은 데이터를 수정해 비관적 락 충돌이 발생한 경우
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handlePessimisticLockFailure(HttpServletRequest request) {
        logRejected(request, ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        return ResponseEntity.status(ErrorCode.CONCURRENT_UPDATE_CONFLICT.getStatus())
                .body(ApiResponse.error(ErrorCode.CONCURRENT_UPDATE_CONFLICT));
    }

    // 예상하지 못한 DB 제약 조건 위반이 발생한 경우
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        logFailed(request, ErrorCode.INTERNAL_SERVER_ERROR, exception);
        return ResponseEntity.internalServerError().body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    // 위에서 처리하지 못한 예외가 발생한 경우
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception, HttpServletRequest request) {
        logFailed(request, ErrorCode.INTERNAL_SERVER_ERROR, exception);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    // 요청 경로는 사용자가 보낸 값이라 '='처럼 logfmt 파싱을 깨는 문자가 들어올 수 있어 따옴표로 감싸 남긴다.
    // 클라이언트 원인 실패(4xx). 예상한 실패이므로 스택 트레이스 없이 INFO로 남긴다.
    private void logRejected(
            HttpServletRequest request,
            ErrorCode errorCode
    ) {
        log.info(
                "event=REQUEST_REJECTED userId={} method={} uri=\"{}\" errorCode={} httpStatus={}",
                getAuthenticatedUserId(),
                request.getMethod(),
                LogValues.quoted(request.getRequestURI()),
                errorCode.name(),
                errorCode.getStatus().value()
        );
    }

    // 예상하지 못한 서버 오류(5xx). 예외를 마지막 인자로 넘겨 스택 트레이스를 남긴다.
    private void logFailed(
            HttpServletRequest request,
            ErrorCode errorCode,
            Exception exception
    ) {
        log.error(
                "event=REQUEST_FAILED userId={} method={} uri=\"{}\" errorCode={} httpStatus={}",
                getAuthenticatedUserId(),
                request.getMethod(),
                LogValues.quoted(request.getRequestURI()),
                errorCode.name(),
                errorCode.getStatus().value(),
                exception
        );
    }

    private Long getAuthenticatedUserId() {
        var authentication =
                SecurityContextHolder.getContext().getAuthentication();

        return authentication != null
                && authentication.getPrincipal() instanceof Long id
                ? id
                : null;
    }
}
