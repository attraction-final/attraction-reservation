package com.example.attractionreservation.infra.portone.client;

import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.infra.portone.config.PortOneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
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

        PortOneProperties properties = new PortOneProperties();
        properties.setStoreId(STORE_ID);
        portOneClient = new PortOneClient(builder.build(), properties);
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
        portOneClient.cancelPayment(PG_ID, "금액 불일치", null);

        //then
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

    // ---- 아래는 리뷰 2·4·5번(머지 전 항목)을 고치면 활성화할 테스트 ----

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
    @Disabled("리뷰 5번: 404 응답의 type 확인 복구 시 활성화")
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
