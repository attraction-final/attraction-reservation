package com.example.attractionreservation.infra.portone.client;

import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.infra.portone.config.PortOneProperties;
import com.example.attractionreservation.infra.portone.dto.PortOneCancelRequest;
import com.example.attractionreservation.infra.portone.dto.PortOneCancelResponse;
import com.example.attractionreservation.infra.portone.dto.PortOneErrorResponse;
import com.example.attractionreservation.infra.portone.dto.PortOnePaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import java.util.Optional;
import java.util.UUID;

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

    private final RestClient portOneRestClient;
    private final PortOneProperties portOneProperties;

    @Override
    public Optional<PaymentGatewayResponse> getPayment(String pgId) {
        try {
            return Optional.ofNullable(portOneRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/payments/{pgId}")
                            .queryParam("storeId", portOneProperties.getStoreId())
                            .build(pgId))
                    .retrieve()
                    .body(PortOnePaymentResponse.class))
                    .map(response -> toGatewayResponse(pgId, response));
        } catch (RestClientResponseException e) {
            if (isPaymentNotFound(e)) {
                return Optional.empty();
            }

            log.warn("PortOne 결제 조회 실패: pgId={}, httpStatus={}, responseBody={}",
                    pgId, e.getStatusCode().value(), e.getResponseBodyAsString());

            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        } catch (RestClientException e) {
            log.warn("PortOne 결제 조회 통신 실패: pgId={}, errorType={}",
                    pgId, e.getClass().getSimpleName(), e);

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
     * 이때는 잠시 기다려 같은 키로 다시 보낸다. 마지막 시도까지 처리 중이면 취소가 반영될 수 있으므로 실패로 알리지 않고
     * UNKNOWN을 반환한다. 실패로 알리면 다음 재시도가 새 키로 취소를 다시 보내게 된다.
     */
    @Override
    public PaymentCancellationResponse cancelPayment(
            String pgId, String reason, Long amount, Long currentCancellableAmount) {
        String idempotencyKey = UUID.randomUUID().toString();
        log.info("PortOne 결제 취소 요청: pgId={}, reason={}, amount={}, currentCancellableAmount={}, idempotencyKey={}",
                pgId, reason, amount, currentCancellableAmount, idempotencyKey);

        // 마지막 시도의 응답이 "앞선 요청 처리 중"이었는지. 재시도를 다 쓴 뒤 실패와 UNKNOWN을 가른다.
        boolean lastAttemptOutstanding = false;
        // 처리 중 응답을 받은 횟수. 대기 시간을 정한다. 처리 중 응답은 통신 실패 뒤 재시도에서만 오므로
        // 시도 번호로 정하면 첫 대기가 1배가 아닌 2배가 된다.
        int outstandingCount = 0;
        for (int attempt = 1; attempt <= MAX_CANCEL_ATTEMPTS; attempt++) {
            try {
                PortOneCancelResponse response = portOneRestClient.post()
                        .uri("/payments/{pgId}/cancel", pgId)
                        // PortOne은 멱등 키를 RFC 8941 문자열(쌍따옴표로 감싼 값)로 받는다.
                        .header("Idempotency-Key", "\"" + idempotencyKey + "\"")
                        .body(new PortOneCancelRequest(
                                reason, portOneProperties.getStoreId(), amount, currentCancellableAmount))
                        .retrieve()
                        .body(PortOneCancelResponse.class);
                return toCancellationResult(pgId, response);
            } catch (RestClientResponseException e) {
                // 같은 키의 앞선 요청이 PortOne에서 처리 중이다. 새 키로 보내면 중복 취소될 수 있으므로 같은 키로 기다려 재시도한다.
                if (isIdempotencyOutstanding(e)) {
                    lastAttemptOutstanding = true;
                    outstandingCount++;
                    log.info("PortOne 결제 취소 앞선 요청 처리 중 (시도 {}/{}): pgId={}",
                            attempt, MAX_CANCEL_ATTEMPTS, pgId);

                    if (attempt < MAX_CANCEL_ATTEMPTS && !waitForOutstandingRequest(outstandingCount)) {
                        log.warn("PortOne 결제 취소 재시도 대기 중 인터럽트됨, 조회로 확인 필요: pgId={}", pgId);
                        return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
                    }
                    continue;
                }

                if (isAlreadyCancelled(e)) {
                    // 부분 취소에서 받으면 다른 경로로 전체 취소된 것이므로 이번 부분 취소가 반영됐다고 볼 수 없다.
                    if (amount != null) {
                        log.warn("PortOne 부분 취소 대상 결제가 이미 취소됨: pgId={}, amount={}", pgId, amount);
                        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_CANCELLED);
                    }

                    log.info("PortOne 결제가 이미 취소됨: pgId={}", pgId);
                    return new PaymentCancellationResponse(null, GatewayCancellationStatus.SUCCEEDED);
                }

                // 앞선 취소가 이미 반영돼 취소 가능 금액이 달라졌을 수 있으므로 재시도하면 안 된다.
                if (isCancellableAmountMismatch(e)) {
                    log.warn("PortOne 취소 가능 금액 불일치: pgId={}, amount={}, currentCancellableAmount={}, responseBody={}",
                            pgId, amount, currentCancellableAmount, e.getResponseBodyAsString());
                    throw new BusinessException(ErrorCode.PAYMENT_CANCELLABLE_AMOUNT_MISMATCH);
                }

                log.warn("PortOne 결제 취소 실패: pgId={}, amount={}, currentCancellableAmount={}, httpStatus={}, responseBody={}",
                        pgId, amount, currentCancellableAmount, e.getStatusCode().value(), e.getResponseBodyAsString());

                throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
            } catch (ResourceAccessException e) {
                // 같은 멱등 키로 재시도하므로 이전 요청이 PortOne에서 처리됐어도 중복 취소되지 않는다.
                lastAttemptOutstanding = false;
                log.warn("PortOne 결제 취소 통신 실패 (시도 {}/{}): pgId={}",
                        attempt, MAX_CANCEL_ATTEMPTS, pgId);
            } catch (RestClientException e) {
                // 성공 응답을 받았지만 본문을 읽지 못한 경우(형식 오류, 처리할 수 없는 content-type).
                // 취소가 반영됐을 수 있으므로 실패로 알리지 않는다.
                if (isUnreadableSuccessResponse(e)) {
                    log.warn("PortOne 결제 취소 응답을 해석하지 못함, 조회로 확인 필요: pgId={}", pgId);
                    return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
                }

                log.warn("PortOne 결제 취소 요청 실패: pgId={}, errorType={}",
                        pgId, e.getClass().getSimpleName());

                throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
            }
        }

        // 끝까지 앞선 요청이 처리 중이면 취소가 반영될 수 있으므로 다시 보내지 않고 조회로 확정하게 한다.
        if (lastAttemptOutstanding) {
            log.warn("PortOne 결제 취소 앞선 요청이 끝까지 처리 중, 조회로 확인 필요: pgId={}", pgId);
            return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
        }

        // 통신 재시도를 모두 실패하면 취소가 됐는지 알 수 없으므로 반드시 실패로 알린다.
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
            log.warn("PortOne 결제 취소 응답에 취소 내역이 없음, 조회로 확인 필요: pgId={}", pgId);
            return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
        }

        PaymentCancellationResponse result = response.cancellation().toGatewayResponse();
        switch (result.status()) {
            case FAILED -> {
                log.warn("PortOne 결제 취소 실패 응답: pgId={}, cancellationId={}", pgId, result.cancellationId());
                throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
            }
            case REQUESTED -> log.info("PortOne 결제 취소 접수됨, 조회로 확인 필요: pgId={}, cancellationId={}",
                    pgId, result.cancellationId());
            case UNKNOWN -> log.warn("PortOne 결제 취소 응답 상태를 알 수 없음, 조회로 확인 필요: pgId={}, cancellationId={}",
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
            log.warn("PortOne 결제 완료 응답에 필수값 누락: pgId={}, hasAmount={}, hasPaidAt={}",
                    pgId, gatewayResponse.totalAmount() != null, gatewayResponse.paidAt() != null);

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
