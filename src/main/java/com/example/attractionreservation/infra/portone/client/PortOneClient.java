package com.example.attractionreservation.infra.portone.client;

import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.LogValues;
import com.example.attractionreservation.infra.portone.config.PortOneProperties;
import com.example.attractionreservation.infra.portone.dto.PortOneCancelRequest;
import com.example.attractionreservation.infra.portone.dto.PortOneCancelResponse;
import com.example.attractionreservation.infra.portone.dto.PortOneErrorResponse;
import com.example.attractionreservation.infra.portone.dto.PortOnePaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class PortOneClient implements PaymentGateway {
    private static final int MAX_CANCEL_ATTEMPTS = 3;
    private static final String ALREADY_CANCELLED = "PAYMENT_ALREADY_CANCELLED";
    // PortOne CancellableAmountConsistencyBrokenError의 type 값 (server-sdk 직렬화 이름 기준)
    private static final String CANCELLABLE_AMOUNT_MISMATCH = "CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN";
    private static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    // 같은 멱등 키의 앞선 요청이 처리 중일 때의 409 오류 type 값 (PortOne REST API V2 문서 멱등성 항목 기준)
    private static final String IDEMPOTENCY_OUTSTANDING_REQUEST = "IDEMPOTENCY_OUTSTANDING_REQUEST";
    // 로그의 target, uri 값. uri는 경로별로 집계할 수 있도록 템플릿으로 남긴다.
    private static final String TARGET = "portone";
    private static final String PAYMENT_URI = "/payments/{pgId}";
    private static final String CANCEL_URI = "/payments/{pgId}/cancel";

    private final RestClient portOneRestClient;
    private final PortOneProperties portOneProperties;

    @Override
    public Optional<PaymentGatewayResponse> getPayment(String pgId) {
        long startedAt = System.nanoTime();
        try {
            // 요청 메트릭의 uri 태그에 경로 템플릿이 남도록 문자열 템플릿으로 요청한다.
            ResponseEntity<PortOnePaymentResponse> response = portOneRestClient.get()
                    .uri(PAYMENT_URI + "?storeId={storeId}", pgId, portOneProperties.getStoreId())
                    .retrieve()
                    .toEntity(PortOnePaymentResponse.class);
            logPaymentFetched(pgId, startedAt, response.getStatusCode().value());

            return Optional.ofNullable(response.getBody())
                    .map(body -> toGatewayResponse(pgId, body));
        } catch (RestClientResponseException e) {
            if (isPaymentNotFound(e)) {
                logPaymentFetched(pgId, startedAt, e.getStatusCode().value());
                return Optional.empty();
            }

            log.warn("event=EXTERNAL_API_FAILED target={} method=GET uri={} pgId={} durationMs={} httpStatus={} reason=\"{}\" errorCode={}",
                    TARGET, PAYMENT_URI, pgId, elapsedMs(startedAt), e.getStatusCode().value(), errorTypeReason(e),
                    ErrorCode.PAYMENT_GATEWAY_ERROR.name());

            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        } catch (RestClientException e) {
            log.warn("event=EXTERNAL_API_FAILED target={} method=GET uri={} pgId={} durationMs={} reason=\"통신 또는 응답 해석 실패\" errorCode={}",
                    TARGET, PAYMENT_URI, pgId, elapsedMs(startedAt), ErrorCode.PAYMENT_GATEWAY_ERROR.name(), e);

            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
    }

    /**
     * 멱등 키는 호출마다 새로 만들고, 한 호출 안의 재시도(통신 실패, 앞선 요청 처리 중)에서만 같은 키를 쓴다.
     * PortOne은 같은 키의 응답(실패 포함)을 3시간 동안 저장해 돌려주므로, 호출 간에 키를 고정하면
     * 한 번 실패한 취소를 다시 시도해도 저장된 실패 응답만 받게 된다.
     * 호출이 겹쳐 같은 결제를 두 번 전체 취소해도 두 번째는 PAYMENT_ALREADY_CANCELLED로 성공 처리된다.
     *
     * <p>통신 실패 뒤 같은 키로 재시도했는데 앞선 요청이 아직 처리 중이면 PortOne은 409 IDEMPOTENCY_OUTSTANDING_REQUEST로 응답한다.
     * 이때는 잠시 기다려 같은 키로 다시 보낸다. 한 번이라도 처리 중 응답을 받았는데 결과를 확인하지 못하고 재시도를 다 쓰면
     * (마지막이 처리 중이든 통신 실패든) 앞선 요청이 PG에 도달한 것이므로 실패로 알리지 않고 UNKNOWN을 반환한다.
     * 실패로 알리면 다음 재시도가 새 키로 취소를 다시 보내게 된다.
     */
    @Override
    public PaymentCancellationResponse cancelPayment(
            String pgId, String reason, Long amount, Long currentCancellableAmount) {
        String idempotencyKey = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        PaymentCancellationResponse response = null;
        try {
            response = cancelWithRetry(pgId, reason, amount, currentCancellableAmount, idempotencyKey);
            return response;
        } finally {
            // 재시도를 포함한 호출 전체를 한 번 남긴다. 실패 원인은 실패 지점의 로그에 있다.
            // 실패하면 취소 상태가 없으므로 status를 남기지 않는다. 전체 취소면 amount는 빈 값이다.
            if (response == null) {
                log.info("event=EXTERNAL_API_CALLED target={} method=POST uri={} pgId={} amount={} idempotencyKey={} durationMs={} result=FAILURE",
                        TARGET, CANCEL_URI, pgId, orEmpty(amount), idempotencyKey, elapsedMs(startedAt));
            } else {
                log.info("event=EXTERNAL_API_CALLED target={} method=POST uri={} pgId={} amount={} idempotencyKey={} durationMs={} result=SUCCESS status={}",
                        TARGET, CANCEL_URI, pgId, orEmpty(amount), idempotencyKey, elapsedMs(startedAt), response.status());
            }
        }
    }

    private PaymentCancellationResponse cancelWithRetry(
            String pgId, String reason, Long amount, Long currentCancellableAmount, String idempotencyKey) {
        // 처리 중 응답을 받은 횟수. 대기 시간을 정하고, 재시도를 다 쓴 뒤 실패와 UNKNOWN을 가른다.
        // 처리 중 응답은 통신 실패 뒤 재시도에서만 오므로 시도 번호로 대기 시간을 정하면 첫 대기가 1배가 아닌 2배가 된다.
        int outstandingCount = 0;
        for (int attempt = 1; attempt <= MAX_CANCEL_ATTEMPTS; attempt++) {
            try {
                return toCancellationResult(pgId, requestCancel(pgId, reason, amount, currentCancellableAmount, idempotencyKey));
            } catch (RestClientResponseException e) {
                if (!isIdempotencyOutstanding(e)) {
                    return handleCancelErrorResponse(pgId, amount, currentCancellableAmount, e);
                }

                // 같은 키의 앞선 요청이 PortOne에서 처리 중이다. 새 키로 보내면 중복 취소될 수 있으므로 같은 키로 기다려 재시도한다.
                outstandingCount++;
                // 마지막 시도면 재시도하지 않으므로 반복문 뒤에서 결과를 남긴다.
                if (attempt == MAX_CANCEL_ATTEMPTS) {
                    continue;
                }
                logCancelRetried(pgId, attempt, "앞선 요청 처리 중");

                if (!waitForOutstandingRequest(outstandingCount)) {
                    log.warn("event=PG_CANCEL_UNCONFIRMED pgId={} reason=\"재시도 대기 중 인터럽트\"", pgId);
                    return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
                }
            } catch (ResourceAccessException e) {
                // 같은 멱등 키로 재시도하므로 이전 요청이 PortOne에서 처리됐어도 중복 취소되지 않는다.
                if (attempt < MAX_CANCEL_ATTEMPTS) {
                    logCancelRetried(pgId, attempt, "통신 실패");
                }
            } catch (RestClientException e) {
                return handleUnreadableOrClientError(pgId, attempt, e);
            }
        }

        // 앞선 요청이 PG에서 처리 중인 것을 확인했으므로 취소가 반영될 수 있다. 다시 보내지 않고 조회로 확정하게 한다.
        if (outstandingCount > 0) {
            log.warn("event=PG_CANCEL_UNCONFIRMED pgId={} reason=\"앞선 요청 처리 결과 미확인\"", pgId);
            return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
        }

        // 통신 재시도를 모두 실패하면 취소가 됐는지 알 수 없으므로 반드시 실패로 알린다.
        log.warn("event=EXTERNAL_API_FAILED target={} method=POST uri={} pgId={} retry={} reason=\"통신 실패 재시도 소진\" errorCode={}",
                TARGET, CANCEL_URI, pgId, MAX_CANCEL_ATTEMPTS - 1, ErrorCode.PAYMENT_CANCELLATION_FAILED.name());
        throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
    }

    // retry는 이어서 보낼 재시도가 몇 번째인지다. 첫 시도가 실패해 다시 보내면 1이다.
    private void logCancelRetried(String pgId, int failedAttempt, String reason) {
        log.warn("event=EXTERNAL_API_RETRIED target={} method=POST uri={} pgId={} retry={} reason=\"{}\"",
                TARGET, CANCEL_URI, pgId, failedAttempt, reason);
    }

    private PortOneCancelResponse requestCancel(
            String pgId, String reason, Long amount, Long currentCancellableAmount, String idempotencyKey) {
        return portOneRestClient.post()
                .uri(CANCEL_URI, pgId)
                // PortOne은 멱등 키를 RFC 8941 문자열(쌍따옴표로 감싼 값)로 받는다.
                .header("Idempotency-Key", "\"" + idempotencyKey + "\"")
                .body(new PortOneCancelRequest(
                        reason, portOneProperties.getStoreId(), amount, currentCancellableAmount))
                .retrieve()
                .body(PortOneCancelResponse.class);
    }

    // 처리 중이 아닌 취소 오류 응답. 전체 취소의 이미 취소됨만 성공으로 반환하고, 나머지는 재시도하지 않고 예외로 알린다.
    private PaymentCancellationResponse handleCancelErrorResponse(
            String pgId, Long amount, Long currentCancellableAmount, RestClientResponseException e) {
        if (isAlreadyCancelled(e)) {
            // 부분 취소에서 받으면 다른 경로로 전체 취소된 것이므로 이번 부분 취소가 반영됐다고 볼 수 없다.
            if (amount != null) {
                log.warn("event=PG_CANCEL_REJECTED pgId={} amount={} reason=\"부분 취소 대상 결제가 이미 전체 취소됨\" errorCode={}",
                        pgId, amount, ErrorCode.PAYMENT_ALREADY_CANCELLED.name());
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_CANCELLED);
            }

            log.info("event=PG_PAYMENT_ALREADY_CANCELLED pgId={}", pgId);
            return new PaymentCancellationResponse(null, GatewayCancellationStatus.SUCCEEDED);
        }

        // 앞선 취소가 이미 반영돼 취소 가능 금액이 달라졌을 수 있으므로 재시도하면 안 된다.
        if (isCancellableAmountMismatch(e)) {
            log.warn("event=PG_CANCEL_REJECTED pgId={} amount={} currentCancellableAmount={} reason=\"취소 가능 금액 불일치\" errorCode={}",
                    pgId, amount, currentCancellableAmount, ErrorCode.PAYMENT_CANCELLABLE_AMOUNT_MISMATCH.name());
            throw new BusinessException(ErrorCode.PAYMENT_CANCELLABLE_AMOUNT_MISMATCH);
        }

        log.warn("event=PG_CANCEL_REJECTED pgId={} amount={} currentCancellableAmount={} httpStatus={} reason=\"{}\" errorCode={}",
                pgId, orEmpty(amount), orEmpty(currentCancellableAmount), e.getStatusCode().value(), errorTypeReason(e),
                ErrorCode.PAYMENT_CANCELLATION_FAILED.name());

        throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
    }

    // attempt는 이번 시도의 순번(첫 시도는 1)이다. 실패 로그의 retry에는 실제로 한 재시도 횟수를 남긴다.
    private PaymentCancellationResponse handleUnreadableOrClientError(String pgId, int attempt, RestClientException e) {
        // 성공 응답을 받았지만 본문을 읽지 못한 경우(형식 오류, 처리할 수 없는 content-type).
        // 취소가 반영됐을 수 있으므로 실패로 알리지 않는다.
        if (isUnreadableSuccessResponse(e)) {
            log.warn("event=PG_CANCEL_UNCONFIRMED pgId={} reason=\"응답 해석 실패\"", pgId);
            return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
        }

        log.warn("event=EXTERNAL_API_FAILED target={} method=POST uri={} pgId={} retry={} reason=\"요청 실패\" errorCode={}",
                TARGET, CANCEL_URI, pgId, attempt - 1, ErrorCode.PAYMENT_CANCELLATION_FAILED.name(), e);

        throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
    }

    // 앞선 요청 처리 중 응답 뒤 재시도 전 대기. 처리 중 응답 횟수에 따라 설정값의 1배, 2배로 늘린다. 인터럽트되면 false
    private boolean waitForOutstandingRequest(int outstandingCount) {
        long delayMillis = portOneProperties.getCancelOutstandingRetryDelay()
                .multipliedBy(1L << (outstandingCount - 1))
                .toMillis();
        try {
            Thread.sleep(delayMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // 취소 응답의 상태로 결과를 정한다. FAILED는 PG가 취소를 거절한 것이므로 예외로 알린다.
    private PaymentCancellationResponse toCancellationResult(String pgId, PortOneCancelResponse response) {
        if (response == null || response.cancellation() == null) {
            log.warn("event=PG_CANCEL_UNCONFIRMED pgId={} reason=\"응답에 취소 내역 없음\"", pgId);
            return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
        }

        PaymentCancellationResponse result = response.cancellation().toGatewayResponse();
        switch (result.status()) {
            case FAILED -> {
                log.warn("event=PG_CANCEL_REJECTED pgId={} cancellationId={} reason=\"PG 취소 실패 응답\" errorCode={}",
                        pgId, result.cancellationId(), ErrorCode.PAYMENT_CANCELLATION_FAILED.name());
                throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
            }
            case REQUESTED -> log.info("event=PG_CANCEL_ACCEPTED pgId={} cancellationId={}",
                    pgId, result.cancellationId());
            case UNKNOWN -> log.warn("event=PG_CANCEL_UNCONFIRMED pgId={} cancellationId={} reason=\"응답 상태 알 수 없음\"",
                    pgId, result.cancellationId());
            case SUCCEEDED -> {
            }
        }
        return result;
    }

    // 결제 완료 응답에 금액이나 결제 시각이 없으면 승인할 수 없으므로 결제사 오류로 처리한다.
    private PaymentGatewayResponse toGatewayResponse(String pgId, PortOnePaymentResponse response) {
        PaymentGatewayResponse gatewayResponse = response.toGatewayResponse(pgId);

        if (gatewayResponse.isPaid() && (gatewayResponse.totalAmount() == null || gatewayResponse.paidAt() == null)) {
            log.warn("event=PG_RESPONSE_INVALID pgId={} reason=\"결제 완료 응답에 금액 또는 결제 시각 누락\" errorCode={}",
                    pgId, ErrorCode.PAYMENT_GATEWAY_ERROR.name());

            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }

        return gatewayResponse;
    }

    private boolean isUnreadableSuccessResponse(RestClientException exception) {
        if (exception.getCause() instanceof HttpMessageNotReadableException) {
            return true;
        }

        return exception instanceof UnknownContentTypeException unknownContentType
                && unknownContentType.getStatusCode().is2xxSuccessful();
    }

    // 경로·상점 설정 오류 등 다른 404를 결제 없음으로 오인하지 않도록 type까지 확인한다.
    private boolean isPaymentNotFound(RestClientResponseException exception) {
        return exception.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)
                && PAYMENT_NOT_FOUND.equals(extractErrorType(exception));
    }

    private boolean isIdempotencyOutstanding(RestClientResponseException exception) {
        return IDEMPOTENCY_OUTSTANDING_REQUEST.equals(extractErrorType(exception));
    }

    private boolean isAlreadyCancelled(RestClientResponseException exception) {
        return ALREADY_CANCELLED.equals(extractErrorType(exception));
    }

    private boolean isCancellableAmountMismatch(RestClientResponseException exception) {
        return CANCELLABLE_AMOUNT_MISMATCH.equals(extractErrorType(exception));
    }

    private void logPaymentFetched(String pgId, long startedAt, int httpStatus) {
        log.info("event=EXTERNAL_API_CALLED target={} method=GET uri={} pgId={} durationMs={} result=SUCCESS httpStatus={}",
                TARGET, PAYMENT_URI, pgId, elapsedMs(startedAt), httpStatus);
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    // 값이 없으면 "null" 대신 빈 값(key=)으로 남긴다.
    private Object orEmpty(Object value) {
        return value == null ? "" : value;
    }

    // 로그 reason 값. 오류 응답에 type이 없으면 "null" 대신 원인을 알 수 없다고 남긴다.
    private String errorTypeReason(RestClientResponseException exception) {
        String errorType = extractErrorType(exception);
        // PortOne이 보낸 외부 값이라 reason="..." 안에 넣을 수 있게 바꾼다.
        return errorType == null ? "오류 응답에 type 없음" : LogValues.quoted(errorType);
    }

    // PortOne 오류 응답의 type 필드. 본문이 없거나 형식이 다르면 null
    private String extractErrorType(RestClientResponseException exception) {
        try {
            PortOneErrorResponse error = exception.getResponseBodyAs(PortOneErrorResponse.class);
            return error == null ? null : error.type();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
