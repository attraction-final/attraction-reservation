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
     *     <li>{@code UNKNOWN}: 취소 요청은 성공 응답을 받았지만 결과를 해석할 수 없거나, 재시도 중 앞선 취소 요청이
     *     PG에서 처리 중이라는 응답을 받았는데 결과를 확인하지 못했다. 취소됐을 수 있으므로 {@code REQUESTED}처럼 조회로 확정해야 한다.
     *     응답에 취소 내역이 없거나 본문을 읽지 못했거나 앞선 요청의 결과를 확인하지 못했으면 취소 내역 ID는 null이고,
     *     취소 상태 값만 알 수 없으면 취소 내역 ID가 있다.</li>
     * </ul>
     * {@code FAILED}는 반환하지 않고 예외로 알린다.
     *
     * @param amount 취소 금액. null이면 전체 취소
     * @throws com.example.attractionreservation.global.exception.BusinessException
     *         PG가 취소에 실패했거나 취소 여부를 확인할 수 없으면 {@code PAYMENT_CANCELLATION_FAILED}
     */
    default PaymentCancellationResponse cancelPayment(String pgId, String reason, Long amount) {
        return cancelPayment(pgId, reason, amount, null);
    }

    /**
     * PG 결제를 취소한다. 부분 취소의 중복 처리를 막기 위해 PG 취소 가능 금액을 함께 보낼 수 있다.
     *
     * <p>"이미 취소됨"을 성공으로 간주하는 것은 전체 취소일 때만이다. 부분 취소에서 이미 취소된 결제는
     * 이번 취소가 반영됐다고 볼 수 없으므로 실패로 알린다.
     *
     * <p>반환값은 {@link #cancelPayment(String, String, Long)}과 같다. {@code REQUESTED}·{@code UNKNOWN}이면
     * 다시 취소하지 말고 {@link #getPayment(String)}의 취소 내역으로 확정해야 한다.
     *
     * @param amount                   취소 금액. null이면 전체 취소
     * @param currentCancellableAmount 이번 취소 전에 PG에 남아 있어야 할 취소 가능 금액. 다르면 PG가 거절한다.
     *                                 null이면 보내지 않는다
     * @throws com.example.attractionreservation.global.exception.BusinessException
     *         부분 취소 대상 결제가 이미 취소됐으면 {@code PAYMENT_ALREADY_CANCELLED},
     *         PG 취소 가능 금액이 {@code currentCancellableAmount}와 다르면 {@code PAYMENT_CANCELLABLE_AMOUNT_MISMATCH}.
     *         둘 다 앞선 취소가 PG에 반영됐을 수 있어 재시도하면 안 된다.
     *         그 밖에 취소 여부를 확인할 수 없으면 {@code PAYMENT_CANCELLATION_FAILED}
     */
    PaymentCancellationResponse cancelPayment(String pgId, String reason, Long amount, Long currentCancellableAmount);
}
