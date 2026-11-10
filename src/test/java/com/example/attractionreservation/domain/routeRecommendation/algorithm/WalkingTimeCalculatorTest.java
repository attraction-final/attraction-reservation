package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WalkingTimeCalculatorTest {

    // application.yaml 기본값과 같은 설정
    private final WalkingTimeCalculator calculator =
            new WalkingTimeCalculator(1.7, 53, 37.2870, 37.2975, 127.1950, 127.2095);

    private static final Coordinate BUMPER_CAR = new Coordinate(37.2923658, 127.1995663);

    @Test
    @DisplayName("위도 0.001도 차이는 직선거리 약 111m다")
    void straightMeters_OneThousandthDegreeLatitude() {
        Coordinate north = new Coordinate(BUMPER_CAR.latitude() + 0.001, BUMPER_CAR.longitude());

        assertThat(WalkingTimeCalculator.straightMeters(BUMPER_CAR, north)).isCloseTo(111.2, within(0.5));
    }

    @Test
    @DisplayName("이동 시간은 직선거리 × 우회 계수 ÷ 보행 속도를 올림한 값이다")
    void minutesBetween_AppliesDetourAndSpeed_RoundsUp() {
        // 111.2m × 1.7 ÷ 53m/분 = 3.57분 → 4분
        Coordinate north = new Coordinate(BUMPER_CAR.latitude() + 0.001, BUMPER_CAR.longitude());

        assertThat(calculator.minutesBetween(BUMPER_CAR, north)).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 좌표 사이의 이동 시간은 0분이다")
    void minutesBetween_SameCoordinate_Zero() {
        assertThat(calculator.minutesBetween(BUMPER_CAR, BUMPER_CAR)).isZero();
    }

    @Test
    @DisplayName("공원 범위 안의 좌표와 경계값은 범위 안, 밖의 좌표는 범위 밖이다")
    void isInsidePark() {
        assertThat(calculator.isInsidePark(BUMPER_CAR)).isTrue();
        assertThat(calculator.isInsidePark(new Coordinate(37.2870, 127.1950))).isTrue();
        assertThat(calculator.isInsidePark(new Coordinate(37.5665, 126.9780))).isFalse(); // 서울시청
        assertThat(calculator.isInsidePark(new Coordinate(37.2923, 127.2100))).isFalse(); // 경도만 벗어남
    }

    @Test
    @DisplayName("우회 계수가 1보다 작거나 보행 속도가 0 이하면 생성할 수 없다")
    void constructor_InvalidSettings_Throws() {
        assertThatThrownBy(() -> new WalkingTimeCalculator(0.9, 53, 37.2870, 37.2975, 127.1950, 127.2095))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WalkingTimeCalculator(1.7, 0, 37.2870, 37.2975, 127.1950, 127.2095))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
