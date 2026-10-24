package com.example.attractionreservation.domain.payment.port;

import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;

import java.util.Optional;

public interface PaymentGateway {

    /**
     * PG에서 결제 정보를 조회한다.
     *
     * <p>구현체는 다음을 보장해야 한다.
     * <ul>
     *     <li>반환값의 {@code pgId}는 요청한 {@code pgId}와 같다.</li>
     *     <li>상태가 {@code PAID}면 {@code totalAmount}와 {@code paidAt}은 null이 아니다.
     *     누락된 응답은 반환하지 않고 {@code PAYMENT_GATEWAY_ERROR}로 처리한다.</li>
     *     <li>지원하지 않는 상태는 {@code UNKNOWN}으로 변환한다.</li>
     * </ul>
     *
     * @return PG에 결제가 없으면 빈 값
     */
    Optional<PaymentGatewayResponse> getPayment(String pgId);

    /**
     * PG 결제를 취소한다. 이미 취소된 결제면 성공으로 간주한다.
     *
     * <p>같은 결제를 여러 번 전체 취소해도 한 번만 환불되어야 한다. 두 번째 이후 호출은
     * "이미 취소됨"을 성공으로 처리해 보장한다. 보상 취소의 즉시 시도와 스케줄러 재시도가 겹쳐도
     * 중복 환불되지 않는 근거다.
     *
     * <p>반환값의 상태로 취소 완료 여부를 판단한다.
     * <ul>
     *     <li>{@code SUCCEEDED}: 취소 완료. 이미 취소된 결제면 취소 내역 ID는 null이다.</li>
     *     <li>{@code REQUESTED}: PG가 취소를 접수했지만 아직 끝나지 않았다. 다시 취소하지 말고
     *     {@link #getPayment(String)}의 취소 내역으로 확정해야 한다.</li>
     *     <li>{@code UNKNOWN}: 취소 요청은 성공 응답을 받았지만 결과를 해석할 수 없다. 취소됐을 수 있으므로
     *     {@code REQUESTED}처럼 조회로 확정해야 한다. 취소 내역 ID는 null이다.</li>
     * </ul>
     * {@code FAILED}는 반환하지 않고 예외로 알린다.
     *
     * @param amount 취소 금액. null이면 전체 취소
     * @throws com.example.attractionreservation.global.exception.BusinessException
     *         PG가 취소에 실패했거나 취소 여부를 확인할 수 없으면 {@code PAYMENT_CANCELLATION_FAILED}
     */
    PaymentCancellationResponse cancelPayment(String pgId, String reason, Long amount);
}
