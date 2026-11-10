package com.example.attractionreservation.domain.routeRecommendation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 현재 위치 기반 방문 순서 추천 요청.
 * 브라우저 Geolocation API(navigator.geolocation.getCurrentPosition)의 값을 그대로 받는다.
 * 서버는 Geolocation API를 호출하지 않는다. 브라우저가 측정한 값을 받기만 한다.
 *
 * - 계산에 쓰는 값: latitude, longitude
 * - 요청 거부 판단에 쓰는 값: accuracy (설정값 이상이면 400)
 * - 경고에 쓰는 값: timestamp (오래된 위치면 STALE_POSITION 경고)
 * - 받아서 응답에 그대로 돌려주는 값: altitude, altitudeAccuracy, heading, speed
 *   (정지 상태·GPS 없는 기기에서는 null 이 흔해 선택값이다)
 */
public record CreateRouteRecommendationRequest(

        // 위도 (WGS84)
        @NotNull(message = "위도는 필수입니다.")
        @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
        @DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.")
        Double latitude,

        // 경도 (WGS84)
        @NotNull(message = "경도는 필수입니다.")
        @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
        @DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.")
        Double longitude,

        // 위치 오차 반경(m). 브라우저가 항상 주는 값이다.
        // 정확도 기준으로 요청을 거부하므로 필수값으로 둔다. (선택값이면 빼고 보내는 것만으로 검사를 우회할 수 있다)
        @NotNull(message = "위치 정확도는 필수입니다.")
        @PositiveOrZero(message = "위치 정확도는 0 이상이어야 합니다.")
        Double accuracy,

        // 해발 고도(m). 해수면 아래는 음수일 수 있어 범위를 제한하지 않는다.
        Double altitude,

        // 고도 오차(m)
        @PositiveOrZero(message = "고도 정확도는 0 이상이어야 합니다.")
        Double altitudeAccuracy,

        // 이동 방향(도). 북쪽 0도 기준 시계 방향, 0 이상 360 미만.
        // 브라우저는 정지 상태(speed = 0)에서 NaN 을 주며, JSON 으로 보내면 null 이 된다.
        @DecimalMin(value = "0.0", message = "방향은 0 이상이어야 합니다.")
        @DecimalMax(value = "360.0", inclusive = false, message = "방향은 360 미만이어야 합니다.")
        Double heading,

        // 이동 속도(m/s)
        @PositiveOrZero(message = "속도는 0 이상이어야 합니다.")
        Double speed,

        // 위치 측정 시각(Unix epoch 밀리초). position.coords 가 아니라 position.timestamp 값이다.
        @Positive(message = "측정 시각은 양수여야 합니다.")
        Long timestamp,

        // 방문할 어트랙션 ID. 정확히 5개. 중복 검사는 Service 에서 한다.
        @NotNull(message = "어트랙션 ID 목록은 필수입니다.")
        @Size(min = 5, max = 5, message = "어트랙션은 정확히 5개 선택해야 합니다.")
        List<@NotNull(message = "어트랙션 ID에 null이 포함될 수 없습니다.")
             @Positive(message = "어트랙션 ID는 양수여야 합니다.") Long> attractionIds
) {
}
