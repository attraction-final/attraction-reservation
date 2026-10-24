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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PortOneClient implements PaymentGateway {
    private static final int MAX_CANCEL_ATTEMPTS = 3;
    private static final String ALREADY_CANCELLED = "PAYMENT_ALREADY_CANCELLED";

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
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException e) {
            log.warn("PortOne 결제 조회 실패: pgId={}, httpStatus={}, responseBody={}",
                    pgId, e.getStatusCode().value(), e.getResponseBodyAsString());

            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        } catch (RestClientException e) {
            log.warn("PortOne 결제 조회 통신 실패: pgId={}, errorType={}",
                    pgId, e.getClass().getSimpleName());

            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
    }

    /**
     * 멱등 키는 호출마다 새로 만들고, 한 호출 안의 통신 재시도에서만 같은 키를 쓴다.
     * PortOne은 같은 키의 응답(실패 포함)을 3시간 동안 저장해 돌려주므로, 호출 간에 키를 고정하면
     * 한 번 실패한 취소를 다시 시도해도 저장된 실패 응답만 받게 된다.
     * 호출이 겹쳐 같은 결제를 두 번 전체 취소해도 두 번째는 PAYMENT_ALREADY_CANCELLED로 성공 처리된다.
     */
    @Override
    public PaymentCancellationResponse cancelPayment(String pgId, String reason, Long amount) {
        String idempotencyKey = UUID.randomUUID().toString();
        log.info("PortOne 결제 취소 요청: pgId={}, reason={}, idempotencyKey={}",
                pgId, reason, idempotencyKey);

        for (int attempt = 1; attempt <= MAX_CANCEL_ATTEMPTS; attempt++) {
            try {
                PortOneCancelResponse response = portOneRestClient.post()
                        .uri("/payments/{pgId}/cancel", pgId)
                        .header("Idempotency-Key", idempotencyKey)
                        .body(new PortOneCancelRequest(reason, portOneProperties.getStoreId(), amount))
                        .retrieve()
                        .body(PortOneCancelResponse.class);
                return toCancellationResult(pgId, response);
            } catch (RestClientResponseException e) {
                if (isAlreadyCancelled(e)) {
                    log.info("PortOne 결제가 이미 취소됨: pgId={}", pgId);
                    return new PaymentCancellationResponse(null, GatewayCancellationStatus.SUCCEEDED);
                }

                log.warn("PortOne 결제 취소 실패: pgId={}, httpStatus={}, responseBody={}",
                        pgId, e.getStatusCode().value(), e.getResponseBodyAsString());

                throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
            } catch (ResourceAccessException e) {
                // 같은 멱등 키로 재시도하므로 이전 요청이 PortOne에서 처리됐어도 중복 취소되지 않는다.
                log.warn("PortOne 결제 취소 통신 실패 (시도 {}/{}): pgId={}",
                        attempt, MAX_CANCEL_ATTEMPTS, pgId);
            } catch (RestClientException e) {
                // 성공 응답을 받았지만 본문을 읽지 못한 경우. 취소가 반영됐을 수 있으므로 실패로 알리지 않는다.
                if (e.getCause() instanceof HttpMessageNotReadableException) {
                    log.warn("PortOne 결제 취소 응답을 해석하지 못함, 조회로 확인 필요: pgId={}", pgId);
                    return new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN);
                }

                log.warn("PortOne 결제 취소 요청 실패: pgId={}, errorType={}",
                        pgId, e.getClass().getSimpleName());

                throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
            }
        }

        // 재시도를 모두 실패하면 취소가 됐는지 알 수 없으므로 반드시 실패로 알린다.
        throw new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED);
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

    private boolean isAlreadyCancelled(RestClientResponseException exception) {
        return ALREADY_CANCELLED.equals(extractErrorType(exception));
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
