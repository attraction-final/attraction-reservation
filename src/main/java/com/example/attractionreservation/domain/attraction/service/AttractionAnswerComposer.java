package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.QuestionIntent;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

//문구 조립은 경우의 수가 많아 파라미터 테스트로 따로 검증하려고 서비스에서 분리한다
@Component
public class AttractionAnswerComposer {

    private static  final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private static final String NO_HEIGHT_LIMIT_INFO = "등록된 키 제한 정보가 없습니다. 현장 안내를 확인해 주세요.";

    private static final String NO_OPERATING_HOURS_INFO = "등록된 운영 시간 정보가 없습니다. 현장 안내를 확인해 주세요.";

    public String compose(Attraction attraction, QuestionIntent intent) {
        String body = switch (intent) {
            case HEIGHT_LIMIT -> heightLimit(attraction) + notOperatingNotice(attraction);
            case OPERATING_HOURS -> operatingHours(attraction) + notOperatingNotice(attraction);
            case OPERATION_STATUS ->  operatingStatus(attraction);
            case OTHER -> throw  new IllegalArgumentException("문구를 만들 수 없는 의도입니다." +intent);
        };
        return withName(attraction, body);
    }
    // 요약에는 상태 문장이 이미 있으므로 "단,..."안내를 덧붙이지 않는다.
    public String composeSummary(Attraction attraction) {
        String body = String.join(
                " ",
                heightLimit(attraction),
                operatingHours(attraction),
                operatingStatus(attraction));
        return withName(attraction, body);
    }

    private String heightLimit(Attraction attraction) {
        Integer minHeightCm = attraction.getMinHeightCm();
        Integer maxHeightCm = attraction.getMaxHeightCm();
        Integer guardianHeightCm = attraction.getGuardianHeightCm();

        if (minHeightCm == null && maxHeightCm == null && guardianHeightCm == null) {
            return NO_HEIGHT_LIMIT_INFO;
        }
        List<String> sentences = new ArrayList<>();
        //검토 필요 -> 아마존 익스프레스는 단독 최소 키와 보호자 기준이 모두 110이라 정확히 110cm면 두 기준이 겹친다.
        //안전한 쪽인 보호자 기준으로 우선해 "초과"로 안내한다. 공지 안내와 맞는지 확인이 필요하다.
        if (guardianHeightCm != null) {
            sentences.add("키 %dcm를 초과하면 혼자 탈 수 있습니다.".formatted(guardianHeightCm));
            sentences.add("키 %dcm 이하는 보호자와 함께 타야 합니다.".formatted(guardianHeightCm));
        } else if (minHeightCm != null) {
            sentences.add("키 %dcm 이상이면 혼자 탈 수 있습니다.".formatted(minHeightCm));
        }
        if (maxHeightCm != null) {
            sentences.add("키 %dcm를 넘으면 탈 수 없습니다.".formatted(maxHeightCm));
        }
        return String.join(" ",sentences);
    }

    private String operatingHours(Attraction attraction) {
        if (attraction.getOpenTime() == null || attraction.getCloseTime() == null) {
            return NO_OPERATING_HOURS_INFO;
        }
        return "운영 시간은 %s ~ %s입니다.".formatted(
                attraction.getOpenTime().format(TIME_FORMATTER),
                attraction.getCloseTime().format(TIME_FORMATTER));
    }

    private  String notOperatingNotice(Attraction attraction) {
        if (attraction.isOperating()) {
            return "";
        }
        return " 단, " +operatingStatus(attraction);
    }

    private String operatingStatus(Attraction attraction) {
        return "현재 %s 상태입니다.".formatted(attraction.getOperationStatus().getDescription());
    }

    private String withName(Attraction attraction, String body) {
        return "[%s] %s".formatted(attraction.getName(), body);
    }
}