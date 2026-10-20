package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.dto.*;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attraction.repository.ReservableSlotView;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Comparator;
import java.util.function.BinaryOperator;

@Service
@RequiredArgsConstructor
public class AttractionService {

    private final AttractionRepository attractionRepository;

    // 어트랙션 등록
    @Transactional
    public CreateAttractionResponse createAttraction(CreateAttractionRequest request) {
        validateNameNotDuplicated(request.name()); // 등록하려는 어트랙션 이름이 이미 존재하는지 검사

        // 요청 DTO의 데이터를 이용해 Attraction 엔티티를 생성, Attraction.create()는 프로젝트에서 별도로 정의한 생성 메서드
        Attraction attraction = Attraction.create(
                request.name(),
                request.description(),
                request.location(),
                request.durationMinutes()
        );
        Attraction savedAttraction = attractionRepository.save(attraction); // 생성한 엔티티를 Repository를 통해 저장, 저장 결과를 savedAttraction 변수에 담음

        return CreateAttractionResponse.from(savedAttraction); // 저장된 엔티티를 응답 DTO로 변환하여 반환
    }

    private void validateNameNotDuplicated(String name) {
        if (attractionRepository.existsByNameAndDeletedAtIsNull(name)) {
            throw new BusinessException(ErrorCode.ATTRACTION_NAME_DUPLICATED);
        }
    }


    // 어트랙션 목록 조회
    @Transactional(readOnly = true)
    public SearchAttractionListResponse findAttractionAll(SearchAttractionCondition condition) {
        LocalDateTime now = KstTime.now();
        LocalDateTime reservableFrom = now.plusMinutes(RESERVATION_DEADLINE_MINUTES);
        LocalDateTime todayEnd = now.toLocalDate().plusDays(1).atStartOfDay();

        Page<Attraction> attractionPage = attractionRepository.searchAttractions(
                condition.keyword(),
                condition.operating(),
                condition.reservable(),
                condition.sort(),
                reservableFrom,
                todayEnd,
                PageRequest.of(condition.page(), condition.size())
        );

        Map<Long, ReservableSlotView> nextSlotMap =
                findNextSlotMap(attractionPage.getContent(), reservableFrom, todayEnd);

        return SearchAttractionListResponse.of(attractionPage, nextSlotMap);
    }

    // 예약 마감 = 슬롯 시작 30분 전
    private static final long RESERVATION_DEADLINE_MINUTES = 30L;

    // 운영중인 어트랙션들의 "오늘 가장 빠른 예약 가능 슬롯"을 어트랙션 ID별로 묶음
    private Map<Long, ReservableSlotView> findNextSlotMap(List<Attraction> attractions,
                                                          LocalDateTime reservableFrom,
                                                          LocalDateTime todayEnd) {
        List<Long> operatingAttractionIds = attractions.stream()
                .filter(Attraction::isOperating)
                .map(Attraction::getId)
                .toList();

        if (operatingAttractionIds.isEmpty()) {
            return Map.of();
        }

        // 같은 어트랙션의 슬롯이 여러 개면 시작 시각이 가장 빠른 슬롯을 남긴다.
        // (쿼리의 정렬 순서에 의존하지 않도록 직접 비교)
        return attractionRepository.findReservableSlots(operatingAttractionIds, reservableFrom, todayEnd)
                .stream()
                .collect(Collectors.toMap(
                        ReservableSlotView::getAttractionId,
                        Function.identity(),
                        BinaryOperator.minBy(Comparator.comparing(ReservableSlotView::getStartsAt))
                ));
    }

    // 어트랙션 상세 조회
    @Transactional(readOnly = true)
    public SearchAttractionResponse findAttractionById(Long attractionId) {
        Attraction attraction = findActiveAttraction(attractionId);
        return SearchAttractionResponse.from(attraction);
    }

    private Attraction findActiveAttraction(Long attractionId) {
        return attractionRepository.findByIdAndDeletedAtIsNull(attractionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND));
    }

}
