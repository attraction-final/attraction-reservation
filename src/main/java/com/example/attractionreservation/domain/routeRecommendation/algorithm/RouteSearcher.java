package com.example.attractionreservation.domain.routeRecommendation.algorithm;
//최적 방문 순서 탐색

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import com.example.attractionreservation.global.util.KstTime;

public class RouteSearcher {

    public RouteSearchResult search(
            LocalDateTime startAt,
            LocalDateTime endAt,
            List<CandidateAttraction> candidates
    ) {
        RouteSearchResult emptyResult = new RouteSearchResult(
                startAt, List.of(), List.of()
        );
        RouteSearchResult bestResult = searchPaths(
                startAt, //경로 전체의 출발 시각
                endAt,
                candidates,
                startAt, //현재 위치에서 출발할 수 있는 시각의 초기값
                new ArrayList<>(),
                new HashSet<>(),
                emptyResult
        );
        Set<Long> includedIds = new HashSet<>();
        for (PlannedVisit visit: bestResult.visits()) {
            includedIds.add(visit.attractionId());
        }
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

    private RouteSearchResult searchPaths(
            LocalDateTime startAt,
            LocalDateTime endAt,
            List<CandidateAttraction> candidates,
            LocalDateTime departureAt,
            List<PlannedVisit> path,
            Set<Long> visitedIds,
            RouteSearchResult bestResult
    ) {
        RouteSearchResult currentResult = new RouteSearchResult(
                startAt, List.copyOf(path), List.of()
        );
        if (isBetter(currentResult, bestResult)) {
            bestResult = currentResult;
        }
        for (CandidateAttraction attraction : candidates) {
            if (visitedIds.contains(attraction.attractionId())) { //
                continue;
            }

            LocalDateTime arrivalAt = departureAt.plusMinutes(
                    attraction.moveMinutes()
            );
            Optional<CandidateSlot> availableSLot = findEarliestAvailableSlot(attraction,arrivalAt,endAt);
            if (availableSLot.isEmpty()) {
                continue;
            }
            CandidateSlot slot = availableSLot.get();
            int waitMinutes = Math.toIntExact(
                    Duration.between(arrivalAt.atZone(KstTime.ZONE), slot.startsAt().atZone(KstTime.ZONE)).toMinutes()
            );

            PlannedVisit visit = new PlannedVisit(
                    path.size() +1,
                    attraction.attractionId(),
                    attraction.attractionName(),
                    slot.slotId(),
                    attraction.moveMinutes(),
                    arrivalAt,
                    waitMinutes,
                    slot.startsAt(),
                    slot.endsAt(),
                    slot.rideMinutes()
            );
            path.add(visit);
            visitedIds.add(attraction.attractionId());

            bestResult = searchPaths(
                    startAt,
                    endAt,
                    candidates,slot.endsAt(),
                    path,
                    visitedIds,
                    bestResult
            );
            visitedIds.remove(attraction.attractionId());
            path.remove(path.size() - 1);
        }
        return bestResult;
    }


    private Optional<CandidateSlot> findEarliestAvailableSlot(
            CandidateAttraction attraction,
            LocalDateTime arrivalAt,
            LocalDateTime endAt
    ) {
        for(CandidateSlot slot : attraction.slots()) {
            if (arrivalAt.isAfter(slot.startsAt())) { // 도착 시각에는 이미 시작한 슬롯이므로 다음 슬롯 확인
                continue;
            }
            if (slot.endsAt().isAfter(endAt)) {
                return Optional.empty();
            }
            return Optional.of(slot);
        }
        return Optional.empty();
    }


    private boolean isBetter(
            RouteSearchResult candidate,
            RouteSearchResult best
    ) {
        // 방문 개수가 많은 경로를 우선으로
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
        int durationComparison =  Integer.compare(
                candidate.totalDurationMinutes(),
                best.totalDurationMinutes()
        );
        if (durationComparison != 0) {
            return durationComparison < 0;
        }
        int firstStartComparison = candidate.visits().get(0).startsAt()
                .compareTo(best.visits().get(0).startsAt());
        if (firstStartComparison != 0) {
            return firstStartComparison <0;
        }
        for (int visitIndex = 0; visitIndex <candidateCount; visitIndex++) {
            int idComparison = Long.compare(
                    candidate.visits().get(visitIndex).attractionId(),
                    best.visits().get(visitIndex).attractionId()
            );
            if (idComparison != 0) {
                return idComparison < 0;
            }
        }
        return false;
    }
}
