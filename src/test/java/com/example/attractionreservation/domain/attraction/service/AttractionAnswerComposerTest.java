package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 답변 문구는 Jev가 아니라 DB 값으로만 정해지므로 글자 그대로 비교한다.
class AttractionAnswerComposerTest {

    private static final LocalTime OPEN = LocalTime.of(12, 45);
    private static final LocalTime CLOSE = LocalTime.of(19, 30);

    private final AttractionAnswerComposer attractionAnswerComposer = new AttractionAnswerComposer();

    // 탑승 정보는 마이그레이션 SQL로만 들어가서 setter가 없다. 테스트에서는 직접 넣어 줌
    private Attraction createAttraction(
            String name, OperationStatus status, Integer minHeightCm, Integer maxHeightCm, Integer guardianHeightCm,
            LocalTime openTime, LocalTime closeTime) {
        Attraction attraction = Attraction.create(name, "설명", "A구역", 10, 5);
        attraction.changeOperationStatus(status);
        ReflectionTestUtils.setField(attraction, "minHeightCm", minHeightCm);
        ReflectionTestUtils.setField(attraction, "maxHeightCm", maxHeightCm);
        ReflectionTestUtils.setField(attraction, "guardianHeightCm", guardianHeightCm);
        ReflectionTestUtils.setField(attraction, "openTime", openTime);
        ReflectionTestUtils.setField(attraction, "closeTime", closeTime);
        return attraction;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
            # 설명                         | 이름              | 상태        | 최소 키 | 최대 키 | 보호자 기준 키 | 기대 문구
            시나리오 1 - 최소 키와 최대 키 | 모니모 러쉬       | OPERATING   | 130  | 195  | null | [모니모 러쉬] 키 130cm 이상이면 혼자 탈 수 있습니다. 키 195cm를 넘으면 탈 수 없습니다.
            최대 키가 없음                 | 범퍼카            | OPERATING   | 120  | null | null | [범퍼카] 키 120cm 이상이면 혼자 탈 수 있습니다.
            최소 키가 없음                 | 렛츠 트위스트     | OPERATING   | null | 195  | null | [렛츠 트위스트] 키 195cm를 넘으면 탈 수 없습니다.
            시나리오 4 - 점검 중           | 렛츠 트위스트     | MAINTENANCE | 140  | 195  | null | [렛츠 트위스트] 키 140cm 이상이면 혼자 탈 수 있습니다. 키 195cm를 넘으면 탈 수 없습니다. 단, 현재 점검 상태입니다.
            일시중단                       | 썬더폴스          | SUSPENDED   | 100  | null | null | [썬더폴스] 키 100cm 이상이면 혼자 탈 수 있습니다. 단, 현재 일시중단 상태입니다.
            시나리오 5 - 보호자 기준 우선  | 아마존 익스프레스 | OPERATING   | 110  | null | 110  | [아마존 익스프레스] 키 110cm를 초과하면 혼자 탈 수 있습니다. 키 110cm 이하는 보호자와 함께 타야 합니다.
            시나리오 10 - 정보 없음        | 허리케인          | OPERATING   | null | null | null | [허리케인] 등록된 키 제한 정보가 없습니다. 현장 안내를 확인해 주세요.
            정보 없음이고 점검 중          | 허리케인          | MAINTENANCE | null | null | null | [허리케인] 등록된 키 제한 정보가 없습니다. 현장 안내를 확인해 주세요. 단, 현재 점검 상태입니다.
            """)
    @DisplayName("키 제한 문구를 조립한다")
    void compose_heightLimit(String description, String name, OperationStatus status,
                             Integer minHeightCm, Integer maxHeightCm, Integer guardianHeightCm, String expected) {
        Attraction attraction = createAttraction(name, status, minHeightCm, maxHeightCm, guardianHeightCm, OPEN, CLOSE);

        String answer = attractionAnswerComposer.compose(attraction, QuestionIntent.HEIGHT_LIMIT);

        assertThat(answer).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "null", textBlock = """
            # 설명                       | 상태        | 시작  | 종료  | 기대 문구
            시나리오 2 - 운영 시간       | OPERATING   | 12:45 | 19:30 | [모니모 러쉬] 운영 시간은 12:45 ~ 19:30입니다.
            정각은 분까지 표시           | OPERATING   | 10:00 | 20:00 | [모니모 러쉬] 운영 시간은 10:00 ~ 20:00입니다.
            점검 중                      | MAINTENANCE | 12:45 | 19:30 | [모니모 러쉬] 운영 시간은 12:45 ~ 19:30입니다. 단, 현재 점검 상태입니다.
            시간 정보 없음               | OPERATING   | null  | null  | [모니모 러쉬] 등록된 운영 시간 정보가 없습니다. 현장 안내를 확인해 주세요.
            종료 시간만 없음             | OPERATING   | 12:45 | null  | [모니모 러쉬] 등록된 운영 시간 정보가 없습니다. 현장 안내를 확인해 주세요.
            시간 정보 없고 점검 중       | MAINTENANCE | null  | null  | [모니모 러쉬] 등록된 운영 시간 정보가 없습니다. 현장 안내를 확인해 주세요. 단, 현재 점검 상태입니다.
            """)
    @DisplayName("운영 시간 문구를 조립한다")
    void compose_operatingHours(String description, OperationStatus status,
                                LocalTime openTime, LocalTime closeTime, String expected) {
        Attraction attraction = createAttraction("모니모 러쉬", status, 130, 195, null, openTime, closeTime);

        String answer = attractionAnswerComposer.compose(attraction, QuestionIntent.OPERATING_HOURS);

        assertThat(answer).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            OPERATING   | [렛츠 트위스트] 현재 운영중 상태입니다.
            MAINTENANCE | [렛츠 트위스트] 현재 점검 상태입니다.
            SUSPENDED   | [렛츠 트위스트] 현재 일시중단 상태입니다.
            """)
    @DisplayName("운영 상태 문구를 조립한다 (시나리오 3)")
    void compose_operationStatus(OperationStatus status, String expected) {
        Attraction attraction = createAttraction("렛츠 트위스트", status, 140, 195, null, null, null);

        String answer = attractionAnswerComposer.compose(attraction, QuestionIntent.OPERATION_STATUS);

        assertThat(answer).isEqualTo(expected);
    }

    @Test
    @DisplayName("요약은 키 제한, 운영 시간, 운영 상태를 한 문단으로 묶고 이름은 한 번만 쓴다 (시나리오 8)")
    void composeSummary() {
        Attraction attraction = createAttraction(
                "모니모 러쉬", OperationStatus.OPERATING, 130, 195, null, OPEN, CLOSE);

        String answer = attractionAnswerComposer.composeSummary(attraction);

        assertThat(answer).isEqualTo("[모니모 러쉬] 키 130cm 이상이면 혼자 탈 수 있습니다. 키 195cm를 넘으면 탈 수 없습니다. "
                + "운영 시간은 12:45 ~ 19:30입니다. 현재 운영중 상태입니다.");
    }

    @Test
    @DisplayName("요약에는 상태 문장이 이미 있어 점검 중이어도 '단,' 안내를 덧붙이지 않는다")
    void composeSummary_notOperating() {
        Attraction attraction = createAttraction(
                "렛츠 트위스트", OperationStatus.MAINTENANCE, 140, 195, null, null, null);

        String answer = attractionAnswerComposer.composeSummary(attraction);

        assertThat(answer).isEqualTo("[렛츠 트위스트] 키 140cm 이상이면 혼자 탈 수 있습니다. 키 195cm를 넘으면 탈 수 없습니다. "
                + "등록된 운영 시간 정보가 없습니다. 현장 안내를 확인해 주세요. 현재 점검 상태입니다.");
    }

    @Test
    @DisplayName("미지원 의도는 서비스에서 먼저 걸러지므로 문구를 만들지 않는다")
    void compose_otherIntent() {
        Attraction attraction = createAttraction(
                "모니모 러쉬", OperationStatus.OPERATING, 130, 195, null, OPEN, CLOSE);

        assertThatThrownBy(() -> attractionAnswerComposer.compose(attraction, QuestionIntent.OTHER))
                .isInstanceOf(IllegalArgumentException.class);
    }
}