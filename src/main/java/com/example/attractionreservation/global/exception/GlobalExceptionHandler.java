package com.example.attractionreservation.global.exception;

import com.example.attractionreservation.global.response.ApiResponse;
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

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    // 예상된 업무 거절은 INFO, 서버 오류는 ERROR로 기록한다.
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();

        Long userId = getAuthenticatedUserId();

        if (errorCode.getStatus().is5xxServerError()) {
            log.error(
                    "event=REQUEST_FAILED "
                            + "userId={} method={} uri={} errorCode={} httpStatus={}",
                    userId,
                    request.getMethod(),
                    request.getRequestURI(),
                    errorCode.name(),
                    errorCode.getStatus().value(),
                    exception
            );
        } else {
            log.info(
                    "event=REQUEST_REJECTED "
                            + "userId={} method={} uri={} errorCode={} httpStatus={}",
                    userId,
                    request.getMethod(),
                    request.getRequestURI(),
                    errorCode.name(),
                    errorCode.getStatus().value()
            );
        }

        // 기존 오류 응답의 상태 코드와 메시지는 유지한다.
        String message = exception.getMessage();

        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.error(
                        errorCode,
                        message != null ? message : errorCode.getMessage()
                ));
    }

    // 요청 본문의 필드 검증에 실패한 경우
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.INVALID_INPUT_VALUE);

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        return ResponseEntity.badRequest()
                .body(ApiResponse.validationFail(fieldErrors));
    }

    // 컨트롤러 메서드 파라미터 검증에 실패한 경우
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.INVALID_INPUT_VALUE);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE));
    }

    // 요청 파라미터의 타입이 맞지 않는 경우
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.INVALID_REQUEST);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST));
    }

    // 존재하지 않는 URL로 요청한 경우
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleUrlNotFound(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.URL_NOT_FOUND);
        return ResponseEntity.status(ErrorCode.URL_NOT_FOUND.getStatus())
                .body(ApiResponse.error(ErrorCode.URL_NOT_FOUND));
    }

    // JSON 문법 오류 또는 요청 DTO로 변환할 수 없는 본문을 보낸 경우
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequestBody(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.INVALID_REQUEST_BODY);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST_BODY));
    }

    // 필수 쿼리 파라미터를 누락한 경우
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.MISSING_REQUEST_VALUE);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.MISSING_REQUEST_VALUE));
    }

    // 지원하지 않는 HTTP 메서드로 요청한 경우
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.METHOD_NOT_ALLOWED);
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED));
    }

    // 지원하지 않는 Content-Type으로 요청한 경우
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getStatus())
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    // 동시에 같은 데이터를 수정해 비관적 락 충돌이 발생한 경우
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handlePessimisticLockFailure(
            HttpServletRequest request
    ) {
        logRequestRejected(request, ErrorCode.CONCURRENT_UPDATE_CONFLICT);
        return ResponseEntity.status(ErrorCode.CONCURRENT_UPDATE_CONFLICT.getStatus())
                .body(ApiResponse.error(ErrorCode.CONCURRENT_UPDATE_CONFLICT));
    }

    // 예상하지 못한 DB 제약 조건 위반이 발생한 경우
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        log.error(
                "event=DATABASE_CONSTRAINT_VIOLATED "
                        + "userId={} method={} uri={} errorCode={} httpStatus={}",
                getAuthenticatedUserId(),
                request.getMethod(),
                request.getRequestURI(),
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                ErrorCode.INTERNAL_SERVER_ERROR.getStatus().value(),
                exception
        );

        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    // 위에서 처리하지 못한 예외가 발생한 경우
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "event=REQUEST_FAILED "
                        + "userId={} method={} uri={} errorCode={} httpStatus={}",
                getAuthenticatedUserId(),
                request.getMethod(),
                request.getRequestURI(),
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                ErrorCode.INTERNAL_SERVER_ERROR.getStatus().value(),
                exception
        );

        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    private void logRequestRejected(
            HttpServletRequest request,
            ErrorCode errorCode
    ) {
        log.info(
                "event=REQUEST_REJECTED "
                        + "userId={} method={} uri={} errorCode={} httpStatus={}",
                getAuthenticatedUserId(),
                request.getMethod(),
                request.getRequestURI(),
                errorCode.name(),
                errorCode.getStatus().value()
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
