package com.example.attractionreservation.domain.routeRecommendation.algorithm;
//최적 방문 순서 탐색

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import com.example.attractionreservation.global.util.KstTime;
import org.springframework.stereotype.Component;
//후보 어트랙션으로 방문 순서와 탑승 슬롯을 결정
@Component
public class RouteSearcher {

    // 첫 이동은 현재 위치 → 어트랙션 추정 구간(startLegs), 이후 이동은 직전 어트랙션 → 다음 어트랙션 활성 구간(travelTimes)으로 탐색한다.
    // 필요한 구간이 없으면(첫 구간 정보 없음 / 구간 없음·비활성) 그 방문 순서는 쓰지 않는다.
    // 대체값이나 역방향(값이 달라서) 값을 쓰지 않는다.-> 정확성이 떨어져서
    public RouteSearchResult search(
            LocalDateTime startAt,
            LocalDateTime endAt,
            List<CandidateAttraction> candidates,
            Map<Long, StartLeg> startLegs,
            TravelTimeTable travelTimes
    ) {
        // 탐색 내내 바뀌지 않는 입력을 하나로 묶는다
        SearchContext context = new SearchContext(startAt, endAt, candidates, startLegs, travelTimes);
        // 아무것도 방문하지 않는 경로를 초기 비교 대상으로 둔다
        RouteSearchResult emptyResult = new RouteSearchResult(
                startAt, List.of(), List.of()
        );
        RouteSearchResult bestResult = searchPaths(
                context,
                startAt, //첫 이동을 시작할 수 있는 시각
                null, //직전 어트랙션 ID. null = 현재 위치에서 출발
                new ArrayList<>(), //현재 만들어 가는 방문 경로
                new HashSet<>(), //현재 경로에서 이미 방문한 어트랙션 ID
                emptyResult
        );
        //최종 선택된 경로에 포함된 어트랙션 ID를 모음
        Set<Long> includedIds = new HashSet<>();
        for (PlannedVisit visit: bestResult.visits()) {
            includedIds.add(visit.attractionId());
        }
        //후보는 있었지만 최종 경로에 포함되지 않은 어트랙션을 찾는다
        List<Long> unreachableIds = new ArrayList<>();
        for (CandidateAttraction attraction : candidates) {
            if (!includedIds.contains(attraction.attractionId())) {
                unreachableIds.add(attraction.attractionId());
            }
        }
        return new RouteSearchResult(
                startAt,
                bestResult.visits(),
                List.copyOf(unreachableIds)
        );
    }

    // 한 번의 탐색 동안 바뀌지 않는 입력
    private record SearchContext(
            LocalDateTime startAt, //전체 소요 시간 계산의 기준
            LocalDateTime endAt, //이용 종료 시각
            List<CandidateAttraction> candidates,
            Map<Long, StartLeg> startLegs,
            TravelTimeTable travelTimes
    ) {
    }

    //현재 경로에서 다음 방문지를 하나씩 선택하며 재귀적으로 탐색
    private RouteSearchResult searchPaths(
            SearchContext context,
            LocalDateTime departureAt,
            Long previousAttractionId,
            List<PlannedVisit> path,
            Set<Long> visitedIds,
            RouteSearchResult bestResult
    ) {
        //현재 경로도 하나의 완성 가능한 추천 결과로 평가
        RouteSearchResult currentResult = new RouteSearchResult(
                context.startAt(), List.copyOf(path), List.of()
        );
        if (isBetter(currentResult, bestResult)) {
            bestResult = currentResult;
        }
        // 다음 방문지로 선택할 수 있는 모든 어트랙션을 시도
        for (CandidateAttraction attraction : context.candidates()) {
            //이미 방문했거나, 구간·슬롯이 없어 갈 수 없으면 empty
            Optional<PlannedVisit> next = visitedIds.contains(attraction.attractionId())
                    ? Optional.empty()
                    : planVisit(context, attraction, previousAttractionId, departureAt, path.size() + 1);
            if (next.isEmpty()) {
                continue;
            }
            PlannedVisit visit = next.get();
            //현재 경로에 방문을 추가하고 중복 방문 방지 집합에도 기록
            path.add(visit);
            visitedIds.add(attraction.attractionId());
            //방금 선택한 슬롯이 끝난 시각부터 다음 방문을 이어 붙인다
            bestResult = searchPaths(
                    context,
                    visit.endsAt(),
                    attraction.attractionId(), //다음 이동의 출발지는 방금 방문한 어트랙션
                    path,
                    visitedIds,
                    bestResult
            );
            //백트래킹 -> 이번 방문 선택을 되돌려 다음 후보를 같은 출발 상태에서 시도
            visitedIds.remove(attraction.attractionId());
            path.remove(path.size() - 1);
        }
        return bestResult;
    }

    //이번 어트랙션의 방문 일정을 만든다. 구간이 없거나 탈 수 있는 슬롯이 없으면 empty
    private Optional<PlannedVisit> planVisit(
            SearchContext context,
            CandidateAttraction attraction,
            Long previousAttractionId,
            LocalDateTime departureAt,
            int sequence
    ) {
        Optional<Move> move = moveTo(attraction, previousAttractionId, context.startLegs(), context.travelTimes());
        if (move.isEmpty()) {
            return Optional.empty();
        }
        Move leg = move.get();
        LocalDateTime arrivalAt = departureAt.plusMinutes(leg.minutes());
        //도착 후 이용할 수 있는 가장 이른 슬롯으로 시간표를 만든다
        return findEarliestAvailableSlot(attraction, arrivalAt, context.endAt())
                .map(slot -> new PlannedVisit(
                        sequence,
                        attraction.attractionId(),
                        attraction.attractionName(),
                        slot.slotId(),
                        leg.minutes(),
                        leg.estimated(),
                        arrivalAt,
                        Math.toIntExact(Duration.between(
                                arrivalAt.atZone(KstTime.ZONE), slot.startsAt().atZone(KstTime.ZONE)).toMinutes()),
                        slot.startsAt(),
                        slot.endsAt(),
                        slot.rideMinutes()
                ));
    }

    //이번 어트랙션까지의 이동
    // - 첫 이동: 현재 위치 → 어트랙션 추정 구간. 좌표 기반 추정이므로 항상 추정으로 표시한다
    // - 이후 이동: 직전 어트랙션 → 이번 어트랙션 활성 구간. 구간의 is_estimated 를 그대로 쓴다
    // 구간이 없으면 empty (대체값을 만들지 않는다)
    private Optional<Move> moveTo(
            CandidateAttraction attraction,
            Long previousAttractionId,
            Map<Long, StartLeg> startLegs,
            TravelTimeTable travelTimes
    ) {
        if (previousAttractionId == null) {
            StartLeg startLeg = startLegs.get(attraction.attractionId());
            return Optional.ofNullable(startLeg).map(leg -> new Move(leg.moveMinutes(), true));
        }
        return travelTimes.find(previousAttractionId, attraction.attractionId())
                .map(edge -> new Move(edge.moveMinutes(), edge.estimated()));
    }

    // 한 구간의 이동 시간과 추정 여부
    private record Move(int minutes, boolean estimated) {
    }

    //도착 시각 이후에 시작하고 이용 종료 시각 안에 끝나는 가장 이른 후보 슬롯을 반환
    private Optional<CandidateSlot> findEarliestAvailableSlot(
            CandidateAttraction attraction,
            LocalDateTime arrivalAt,
            LocalDateTime endAt
    ) {
        for(CandidateSlot slot : attraction.slots()) {
            // 도착 시각에는 이미 시작한 슬롯이므로 다음 슬롯 확인, 도착 시간과 슬롯 시작 시각이 같으면 허용
            if (arrivalAt.isAfter(slot.startsAt())) {
                continue;
            }
            //이 슬롯이 이용 종료 제한을 넘으면 방문할 수 없음
            if (slot.endsAt().isAfter(endAt)) {
                return Optional.empty();
            }
            //정렬된 순서에서 처음 발견한 유효 슬롯이므로 바로 선택
            return Optional.of(slot);
        }
        // 모든 슬롯이 이미 시작했거나, 슬롯 목록이 비어 있는 경우
        return Optional.empty();
    }

    //새 경로가 기존 최선 경로보다 우선하는지 판단
    private boolean isBetter(
            RouteSearchResult candidate,
            RouteSearchResult best
    ) {
        // 1순위: 방문 개수가 많은 경로
        int candidateCount = candidate.visits().size();
        int bestCount = best.visits().size();
        if (candidateCount != bestCount) {
            // 개수가 다르면 다음 기준을 비교하지 않고,
            // 새 경로의 방문 개수가 더 많은지 반환
            return candidateCount > bestCount;
        }
        // 방문 개수가 같으면 아래에서 총 소요 시간을 비교하기

        if (candidate.isEmpty()) {
            return false;
        }
        //2순위: 전체 소요 시간이 짧은 경로
        // compare(a, b):
        // - 음수: a가 작다
        // - 0: 같다
        // - 양수: a가 크다
        int durationComparison =  Integer.compare(
                candidate.totalDurationMinutes(),
                best.totalDurationMinutes()
        );
        if (durationComparison != 0) {
            return durationComparison < 0;
        }
        //3순위: 첫 번째 슬롯의 탑승 시작 시각이 빠른 경로
        int firstStartComparison = candidate.visits().get(0).startsAt()
                .compareTo(best.visits().get(0).startsAt());
        if (firstStartComparison != 0) {
            return firstStartComparison <0;
        }
        //4순위: 방문 순서대로 어트랙션 ID를 사전순 비교
        for (int visitIndex = 0; visitIndex <candidateCount; visitIndex++) {
            int idComparison = Long.compare(
                    candidate.visits().get(visitIndex).attractionId(),
                    best.visits().get(visitIndex).attractionId()
            );
            if (idComparison != 0) {
                return idComparison < 0;
            }
        }
        // 정의된 모든 비교 기준이 같으면 기존 결과를 유지
        return false;
    }
}