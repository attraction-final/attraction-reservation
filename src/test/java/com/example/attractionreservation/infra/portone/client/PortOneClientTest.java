package com.example.attractionreservation.infra.portone.client;

import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.infra.portone.config.PortOneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PortOneClientTest {

    private static final String BASE_URL = "https://api.portone.test";
    private static final String STORE_ID = "store-id";
    private static final String PG_ID = "pg-id";
    private static final String PAYMENT_URL = BASE_URL + "/payments/" + PG_ID + "?storeId=" + STORE_ID;
    private static final String CANCEL_URL = BASE_URL + "/payments/" + PG_ID + "/cancel";

    private MockRestServiceServer server;
    private PortOneClient portOneClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();

        portOneClient = new PortOneClient(builder.build(), properties(Duration.ZERO));
    }

    private static PortOneProperties properties(Duration cancelOutstandingRetryDelay) {
        PortOneProperties properties = new PortOneProperties();
        properties.setStoreId(STORE_ID);
        properties.setCancelOutstandingRetryDelay(cancelOutstandingRetryDelay);
        return properties;
    }

    // 같은 멱등 키의 앞선 요청이 처리 중일 때의 PortOne 응답
    private static ResponseCreator outstandingRequest() {
        return withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"type":"IDEMPOTENCY_OUTSTANDING_REQUEST","message":"처리 중인 요청입니다."}
                        """);
    }

    @Test
    @DisplayName("결제 완료 응답이면 상태, 금액, 결제 완료 시각을 변환해 반환한다.")
    void getPayment_paid_Success() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": "pg-id",
                          "status": "PAID",
                          "amount": { "total": 10000, "paid": 10000 },
                          "paidAt": "2026-10-20T01:00:00Z",
                          "orderName": "unknown field"
                        }
                        """, MediaType.APPLICATION_JSON));

        //when
        Optional<PaymentGatewayResponse> response = portOneClient.getPayment(PG_ID);

        //then
        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo(GatewayPaymentStatus.PAID);
        assertThat(response.get().totalAmount()).isEqualTo(10000L);
        assertThat(response.get().paidAt()).isEqualTo(Instant.parse("2026-10-20T01:00:00Z"));
        server.verify();
    }

    @Test
    @DisplayName("부분 취소된 결제 응답이면 상태와 취소된 누적 금액을 변환해 반환한다.")
    void getPayment_partialCancelled_CancelledAmount() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": "pg-id",
                          "status": "PARTIAL_CANCELLED",
                          "amount": { "total": 10000, "paid": 7000, "cancelled": 3000 },
                          "paidAt": "2026-10-20T01:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        //when
        Optional<PaymentGatewayResponse> response = portOneClient.getPayment(PG_ID);

        //then
        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo(GatewayPaymentStatus.PARTIAL_CANCELLED);
        assertThat(response.get().totalAmount()).isEqualTo(10000L);
        assertThat(response.get().cancelledAmount()).isEqualTo(3000L);
        server.verify();
    }

    @Test
    @DisplayName("결제 완료가 아닌 응답은 결제 완료 시각이 없어도 그대로 반환한다.")
    void getPayment_notPaidWithoutPaidAt_Success() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "id": "pg-id", "status": "READY", "amount": { "total": 10000 } }
                        """, MediaType.APPLICATION_JSON));

        //when
        Optional<PaymentGatewayResponse> response = portOneClient.getPayment(PG_ID);

        //then
        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo(GatewayPaymentStatus.READY);
        assertThat(response.get().paidAt()).isNull();
    }

    @Test
    @DisplayName("결제사에 결제가 없으면(404) 빈 값을 반환한다.")
    void getPayment_notFound_Empty() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PAYMENT_NOT_FOUND","message":"결제 건이 존재하지 않습니다."}
                                """));

        //when
        Optional<PaymentGatewayResponse> response = portOneClient.getPayment(PG_ID);

        //then
        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("결제사 서버 오류면 결제사 오류로 처리한다.")
    void getPayment_serverError_Fail() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        //when & then
        assertThatThrownBy(() -> portOneClient.getPayment(PG_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage());
    }

    @Test
    @DisplayName("결제사 통신에 실패하면 결제사 오류로 처리한다.")
    void getPayment_timeout_Fail() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        //when & then
        assertThatThrownBy(() -> portOneClient.getPayment(PG_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage());
    }

    @Test
    @DisplayName("전체 취소 요청에는 취소 금액을 보내지 않는다.")
    void cancelPayment_full_Success() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.storeId").value(STORE_ID))
                .andExpect(jsonPath("$.reason").value("금액 불일치"))
                .andExpect(jsonPath("$.amount").doesNotExist())
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
    }

    @Test
    @DisplayName("취소 요청의 멱등 키는 쌍따옴표로 감싼 문자열로 보낸다.")
    void cancelPayment_idempotencyKey_SentAsQuotedString() {
        //given
        List<String> idempotencyKeys = new ArrayList<>();
        server.expect(requestTo(CANCEL_URL))
                .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(idempotencyKeys).singleElement().asString().matches("^\"[0-9a-f-]{36}\"$");
    }

    @Test
    @DisplayName("부분 취소 요청에는 취소 금액을 보낸다.")
    void cancelPayment_partial_SendAmount() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andExpect(jsonPath("$.amount").value(3000))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L);

        //then
        server.verify();
    }

    @Test
    @DisplayName("PG 취소 가능 금액을 넘기면 취소 요청에 함께 보낸다.")
    void cancelPayment_withCancellableAmount_SendCurrentCancellableAmount() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.currentCancellableAmount").value(10000))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L, 10000L);

        //then
        server.verify();
    }

    @Test
    @DisplayName("PG 취소 가능 금액을 넘기지 않으면 취소 요청에 포함하지 않는다.")
    void cancelPayment_withoutCancellableAmount_OmitCurrentCancellableAmount() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andExpect(jsonPath("$.currentCancellableAmount").doesNotExist())
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L);

        //then
        server.verify();
    }

    @Test
    @DisplayName("부분 취소는 같은 금액이어도 호출마다 다른 멱등 키를 보낸다.")
    void cancelPayment_partialTwice_DifferentIdempotencyKey() {
        //given
        List<String> idempotencyKeys = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(CANCEL_URL))
                    .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                    .andRespond(withSuccess("""
                            { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                            """, MediaType.APPLICATION_JSON));
        }

        //when
        portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L);
        portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L);

        //then
        assertThat(idempotencyKeys.get(0)).isNotEqualTo(idempotencyKeys.get(1));
    }

    @Test
    @DisplayName("이미 취소된 결제를 취소하면 성공으로 처리한다.")
    void cancelPayment_alreadyCancelled_Success() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PAYMENT_ALREADY_CANCELLED","message":"이미 취소된 결제입니다."}
                                """));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("부분 취소 대상 결제가 이미 취소됐으면 성공으로 보지 않고 재시도 없이 이미 취소됨으로 처리한다.")
    void cancelPayment_partialAlreadyCancelled_Fail() {
        //given
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PAYMENT_ALREADY_CANCELLED","message":"이미 취소된 결제입니다."}
                                """));

        //when & then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L, 10000L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_ALREADY_CANCELLED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("PG 취소 가능 금액이 맞지 않아 거절되면 재시도 없이 취소 가능 금액 불일치로 처리한다.")
    void cancelPayment_cancellableAmountMismatch_Fail() {
        //given
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN","message":"취소 가능 잔액 검증에 실패했습니다."}
                                """));

        //when & then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L, 10000L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCELLABLE_AMOUNT_MISMATCH.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("이미 취소된 경우가 아닌 취소 오류 응답은 재시도 없이 결제 취소 실패로 처리한다.")
    void cancelPayment_errorResponse_FailWithoutRetry() {
        //given
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PG_PROVIDER","message":"PG사 오류"}
                                """));

        //when & then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PG_ID, "금액 불일치", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("통신 실패 후 재시도에 성공하면 같은 멱등 키로 다시 요청하고 성공으로 처리한다.")
    void cancelPayment_timeoutThenSuccess_RetryWithSameKey() {
        //given
        List<String> idempotencyKeys = new ArrayList<>();
        server.expect(requestTo(CANCEL_URL))
                .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(CANCEL_URL))
                .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(idempotencyKeys).hasSize(2);
        assertThat(idempotencyKeys.get(0)).isNotBlank().isEqualTo(idempotencyKeys.get(1));
    }

    @Test
    @DisplayName("재시도를 모두 실패하면 성공으로 끝내지 않고 결제 취소 실패로 처리한다.")
    void cancelPayment_timeoutAllAttempts_Fail() {
        //given
        server.expect(ExpectedCount.times(3), requestTo(CANCEL_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        //when & then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PG_ID, "금액 불일치", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("통신 실패 뒤 앞선 요청 처리 중 응답을 받고 재시도에 성공하면 모든 요청을 같은 멱등 키로 보내고 성공으로 처리한다.")
    void cancelPayment_outstandingThenSuccess_RetryWithSameKey() {
        //given
        List<String> idempotencyKeys = new ArrayList<>();
        server.expect(requestTo(CANCEL_URL))
                .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(CANCEL_URL))
                .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(outstandingRequest());
        server.expect(requestTo(CANCEL_URL))
                .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED));
        assertThat(idempotencyKeys).hasSize(3).doesNotContainNull().containsOnly(idempotencyKeys.get(0));
    }

    @Test
    @DisplayName("모든 시도가 앞선 요청 처리 중 응답이면 취소가 반영될 수 있으므로 실패로 보지 않고 UNKNOWN을 반환한다.")
    void cancelPayment_outstandingAllAttempts_Unknown() {
        //given
        List<String> idempotencyKeys = new ArrayList<>();
        server.expect(ExpectedCount.times(3), requestTo(CANCEL_URL))
                .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(outstandingRequest());

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "부분 환불", 3000L, 10000L);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN));
        assertThat(idempotencyKeys).hasSize(3).containsOnly(idempotencyKeys.get(0));
    }

    @Test
    @DisplayName("통신 실패 뒤 마지막 시도가 앞선 요청 처리 중 응답이면 UNKNOWN을 반환한다.")
    void cancelPayment_timeoutThenOutstandingLast_Unknown() {
        //given
        server.expect(ExpectedCount.times(2), requestTo(CANCEL_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(CANCEL_URL))
                .andRespond(outstandingRequest());

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN));
    }

    @Test
    @DisplayName("앞선 요청 처리 중 응답 뒤 마지막 시도가 통신 실패로 끝나면 결제 취소 실패로 처리한다.")
    void cancelPayment_outstandingThenTimeoutLast_Fail() {
        //given
        server.expect(ExpectedCount.times(2), requestTo(CANCEL_URL))
                .andRespond(outstandingRequest());
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        //when & then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PG_ID, "금액 불일치", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("앞선 요청 처리 중 응답 뒤 다른 취소 오류 응답을 받으면 더 재시도하지 않고 결제 취소 실패로 처리한다.")
    void cancelPayment_outstandingThenErrorResponse_FailWithoutRetry() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(outstandingRequest());
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PG_PROVIDER","message":"PG사 오류"}
                                """));

        //when & then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PG_ID, "금액 불일치", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("앞선 요청 처리 중 응답 뒤 전체 취소가 이미 취소됨으로 응답하면 성공으로 처리한다.")
    void cancelPayment_outstandingThenAlreadyCancelled_Success() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(outstandingRequest());
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"PAYMENT_ALREADY_CANCELLED","message":"이미 취소된 결제입니다."}
                                """));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("앞선 요청 처리 중 응답을 받으면 설정한 대기 시간의 1배, 2배를 기다린 뒤 재시도한다.")
    void cancelPayment_outstanding_WaitBeforeRetry() {
        //given
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer delayedServer = MockRestServiceServer.bindTo(builder).build();
        PortOneClient delayedClient = new PortOneClient(builder.build(), properties(Duration.ofMillis(50)));
        delayedServer.expect(ExpectedCount.times(2), requestTo(CANCEL_URL))
                .andRespond(outstandingRequest());
        delayedServer.expect(requestTo(CANCEL_URL))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        long startedAt = System.nanoTime();
        PaymentCancellationResponse response = delayedClient.cancelPayment(PG_ID, "금액 불일치", null);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        //then
        delayedServer.verify();
        assertThat(response.status()).isEqualTo(GatewayCancellationStatus.SUCCEEDED);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @Test
    @DisplayName("통신 실패 뒤 처음 받은 처리 중 응답은 시도 번호와 무관하게 설정한 대기 시간의 1배만 기다린다.")
    void cancelPayment_timeoutThenOutstanding_WaitBaseDelay() {
        //given
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer delayedServer = MockRestServiceServer.bindTo(builder).build();
        PortOneClient delayedClient = new PortOneClient(builder.build(), properties(Duration.ofSeconds(1)));
        delayedServer.expect(requestTo(CANCEL_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        delayedServer.expect(requestTo(CANCEL_URL))
                .andRespond(outstandingRequest());
        delayedServer.expect(requestTo(CANCEL_URL))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        long startedAt = System.nanoTime();
        PaymentCancellationResponse response = delayedClient.cancelPayment(PG_ID, "금액 불일치", null);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        //then
        delayedServer.verify();
        assertThat(response.status()).isEqualTo(GatewayCancellationStatus.SUCCEEDED);
        // 시도 번호(2회차)로 정하면 2배인 2초를 기다린다. 상한에 1초 여유를 둬 느린 환경에서도 구분한다.
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(1)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("재시도 대기 중 인터럽트되면 더 재시도하지 않고 인터럽트 상태를 유지한 채 UNKNOWN을 반환한다.")
    void cancelPayment_interruptedWhileWaiting_Unknown() {
        //given
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer delayedServer = MockRestServiceServer.bindTo(builder).build();
        PortOneClient delayedClient = new PortOneClient(builder.build(), properties(Duration.ofSeconds(10)));
        delayedServer.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(outstandingRequest());

        //when
        Thread.currentThread().interrupt();
        PaymentCancellationResponse response;
        boolean interrupted;
        try {
            response = delayedClient.cancelPayment(PG_ID, "금액 불일치", null);
        } finally {
            interrupted = Thread.interrupted();
        }

        //then
        delayedServer.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN));
        assertThat(interrupted).isTrue();
    }

    @Test
    @DisplayName("취소 완료 응답이면 취소 내역 ID와 SUCCEEDED를 반환한다.")
    void cancelPayment_succeeded_ReturnSucceeded() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        assertThat(response).isEqualTo(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED));
    }

    @Test
    @DisplayName("취소 요청 접수 응답이면 완료로 보지 않고 취소 내역 ID와 REQUESTED를 반환한다.")
    void cancelPayment_requested_ReturnRequested() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "REQUESTED" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        assertThat(response).isEqualTo(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED));
    }

    @Test
    @DisplayName("성공 응답이어도 취소 상태가 FAILED면 재시도 없이 결제 취소 실패로 처리한다.")
    void cancelPayment_failedStatus_FailWithoutRetry() {
        //given
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "FAILED" } }
                        """, MediaType.APPLICATION_JSON));

        //when & then
        assertThatThrownBy(() -> portOneClient.cancelPayment(PG_ID, "금액 불일치", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("성공 응답에 알 수 없는 취소 상태가 오면 실패로 보지 않고 UNKNOWN을 반환한다.")
    void cancelPayment_unsupportedStatus_Unknown() {
        //given
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withSuccess("""
                        { "cancellation": { "id": "cancel-1", "status": "PROCESSING" } }
                        """, MediaType.APPLICATION_JSON));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        assertThat(response).isEqualTo(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.UNKNOWN));
    }

    @Test
    @DisplayName("성공 응답에 취소 내역이 없으면 취소됐을 수 있으므로 실패로 보지 않고 UNKNOWN을 반환한다.")
    void cancelPayment_withoutCancellation_Unknown() {
        //given
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN));
    }

    @Test
    @DisplayName("성공 응답 본문을 해석할 수 없으면 재시도 없이 UNKNOWN을 반환한다.")
    void cancelPayment_unreadableBody_Unknown() {
        //given
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withSuccess("{ not json", MediaType.APPLICATION_JSON));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN));
    }

    @Test
    @DisplayName("성공 응답이 처리할 수 없는 content-type이면 취소됐을 수 있으므로 재시도 없이 UNKNOWN을 반환한다.")
    void cancelPayment_unsupportedContentType_Unknown() {
        //given
        server.expect(ExpectedCount.once(), requestTo(CANCEL_URL))
                .andRespond(withSuccess("<html>ok</html>", MediaType.TEXT_HTML));

        //when
        PaymentCancellationResponse response = portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
        server.verify();
        assertThat(response).isEqualTo(new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN));
    }

    @Test
    @DisplayName("결제 조회 응답의 취소 내역을 취소 내역 ID와 상태로 변환한다.")
    void getPayment_withCancellations_Mapped() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "id": "pg-id", "status": "PAID", "amount": { "total": 10000 }, "paidAt": "2026-10-20T01:00:00Z",
                          "cancellations": [
                            { "id": "cancel-1", "status": "REQUESTED" },
                            { "id": "cancel-2", "status": "SUCCEEDED" }
                          ] }
                        """, MediaType.APPLICATION_JSON));

        //when
        PaymentGatewayResponse response = portOneClient.getPayment(PG_ID).orElseThrow();

        //then
        assertThat(response.cancellations()).containsExactly(
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED),
                new PaymentCancellationResponse("cancel-2", GatewayCancellationStatus.SUCCEEDED));
        assertThat(response.findCancellation("cancel-2")).map(PaymentCancellationResponse::status)
                .contains(GatewayCancellationStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("결제 조회 응답의 취소 내역 금액을 함께 변환한다.")
    void getPayment_cancellationTotalAmount_Mapped() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "id": "pg-id", "status": "PARTIAL_CANCELLED", "amount": { "total": 10000, "cancelled": 3000 },
                          "paidAt": "2026-10-20T01:00:00Z",
                          "cancellations": [ { "id": "cancel-1", "status": "REQUESTED", "totalAmount": 3000 } ] }
                        """, MediaType.APPLICATION_JSON));

        //when
        PaymentGatewayResponse response = portOneClient.getPayment(PG_ID).orElseThrow();

        //then
        assertThat(response.cancellations()).containsExactly(
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED, 3000L));
    }

    @Test
    @DisplayName("결제 조회 응답에 취소 내역이 없으면 빈 목록으로 변환한다.")
    void getPayment_withoutCancellations_EmptyList() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "id": "pg-id", "status": "PAID", "amount": { "total": 10000 }, "paidAt": "2026-10-20T01:00:00Z" }
                        """, MediaType.APPLICATION_JSON));

        //when
        PaymentGatewayResponse response = portOneClient.getPayment(PG_ID).orElseThrow();

        //then
        assertThat(response.cancellations()).isEmpty();
        assertThat(response.findCancellation("cancel-1")).isEmpty();
    }

    @Test
    @DisplayName("결제 완료 응답에 결제 완료 시각이 없으면 결제사 오류로 처리한다.")
    void getPayment_paidWithoutPaidAt_Fail() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "id": "pg-id", "status": "PAID", "amount": { "total": 10000 } }
                        """, MediaType.APPLICATION_JSON));

        //when & then
        assertThatThrownBy(() -> portOneClient.getPayment(PG_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage());
    }

    @Test
    @DisplayName("결제 완료 응답에 금액이 없으면 결제사 오류로 처리한다.")
    void getPayment_paidWithoutAmount_Fail() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "id": "pg-id", "status": "PAID", "paidAt": "2026-10-20T01:00:00Z" }
                        """, MediaType.APPLICATION_JSON));

        //when & then
        assertThatThrownBy(() -> portOneClient.getPayment(PG_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage());
    }

    @Test
    @DisplayName("지원하지 않는 결제 상태는 UNKNOWN으로 변환한다.")
    void getPayment_unsupportedStatus_Unknown() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "id": "pg-id", "status": "VIRTUAL_ACCOUNT_ISSUED", "amount": { "total": 10000 } }
                        """, MediaType.APPLICATION_JSON));

        //when
        Optional<PaymentGatewayResponse> response = portOneClient.getPayment(PG_ID);

        //then
        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo(GatewayPaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("응답의 결제 ID가 아니라 요청한 pgId를 그대로 반환한다.")
    void getPayment_responseWithoutId_ReturnRequestedPgId() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withSuccess("""
                        { "status": "PAID", "amount": { "total": 10000 }, "paidAt": "2026-10-20T01:00:00Z" }
                        """, MediaType.APPLICATION_JSON));

        //when
        Optional<PaymentGatewayResponse> response = portOneClient.getPayment(PG_ID);

        //then
        assertThat(response).isPresent();
        assertThat(response.get().pgId()).isEqualTo(PG_ID);
    }

    @Test
    @DisplayName("결제사 404 응답이 결제 없음 오류가 아니면 결제사 오류로 처리한다.")
    void getPayment_notFoundWithoutPaymentNotFoundType_Fail() {
        //given
        server.expect(requestTo(PAYMENT_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"type":"UNKNOWN_PATH","message":"not found"}
                                """));

        //when & then
        assertThatThrownBy(() -> portOneClient.getPayment(PG_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage());
    }

    @Test
    @DisplayName("전체 취소도 호출마다 다른 멱등 키를 보낸다. PortOne이 같은 키의 실패 응답을 저장하므로 재시도는 새 키여야 한다.")
    void cancelPayment_fullCancelTwice_DifferentIdempotencyKey() {
        //given
        List<String> idempotencyKeys = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(CANCEL_URL))
                    .andExpect(request -> idempotencyKeys.add(request.getHeaders().getFirst("Idempotency-Key")))
                    .andRespond(withSuccess("""
                            { "cancellation": { "id": "cancel-1", "status": "SUCCEEDED" } }
                            """, MediaType.APPLICATION_JSON));
        }

        //when
        portOneClient.cancelPayment(PG_ID, "결제 금액 불일치", null);
        portOneClient.cancelPayment(PG_ID, "결제 금액 불일치", null);

        //then
        assertThat(idempotencyKeys.get(0)).isNotEqualTo(idempotencyKeys.get(1));
    }
}
