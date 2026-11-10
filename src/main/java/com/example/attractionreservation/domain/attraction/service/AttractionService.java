package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.*;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.domain.attractionSlot.repository.ReservableSlotView;
import com.example.attractionreservation.global.config.CacheConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.AfterCommit;
import com.example.attractionreservation.global.util.KstTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AttractionService {

    private final AttractionRepository attractionRepository;
    private final UpcomingReservationReader upcomingReservationReader;
    private final AttractionSlotRepository attractionSlotRepository; // nextSlot 조회용
    private final AttractionDetailCacheEvictor attractionDetailCacheEvictor; // ★ 추가: 상세 캐시 삭제용

    // 어트랙션 등록
    @Transactional
    public CreateAttractionResponse createAttraction(Long adminId, CreateAttractionRequest request) {
        validateNameNotDuplicated(request.name()); // 등록하려는 어트랙션 이름이 이미 존재하는지 검사

        // 요청 DTO의 데이터를 이용해 Attraction 엔티티를 생성, Attraction.create()는 프로젝트에서 별도로 정의한 생성 메서드
        Attraction attraction = Attraction.create(
                request.name(),
                request.description(),
                request.location(),
                request.durationMinutes(),
                request.entranceMoveMinutes() // 추가
        );
        Attraction savedAttraction;
        try {
            // DB 반영까지 시도하여 이름 중복 제약 위반을 이 catch에서 처리한다.
            savedAttraction = attractionRepository.saveAndFlush(attraction);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 사전 중복 검사를 통과했더라도 이름 중복은 기존 업무 예외로 변환한다.
            if (isAttractionNameDuplicate(e)) {
                throw new BusinessException(ErrorCode.ATTRACTION_NAME_DUPLICATED);
            }
            // 다른 DB 제약 위반은 이름 중복으로 처리하지 않고 원래 예외를 전달한다.
            throw e;
        }

        // [로그 수정] 커밋 뒤에 남긴다. 로그에 쓸 값은 람다 밖에서 미리 꺼낸다.
        Long attractionId = savedAttraction.getId();
        Integer durationMinutes = savedAttraction.getDurationMinutes();
        AfterCommit.run(() -> log.info("event=ATTRACTION_CREATED attractionId={} userId={} durationMinutes={}",
                attractionId, adminId, durationMinutes));

        return CreateAttractionResponse.from(savedAttraction); // 저장된 엔티티를 응답 DTO로 변환하여 반환
    }

    private void validateNameNotDuplicated(String name) {
        if (attractionRepository.existsByNameAndDeletedAtIsNull(name)) {
            throw new BusinessException(ErrorCode.ATTRACTION_NAME_DUPLICATED);
        }
    }

    // 원인 예외를 순회하여 어트랙션 이름 중복 제약 위반인지 판별한다.
    // 등록·수정에서 함께 사용하며, 업무 예외로 변환하는 처리는 호출부에서 수행한다.
    private boolean isAttractionNameDuplicate(DataIntegrityViolationException exception) {
        Throwable throwable = exception;

        while (throwable != null) {
            // Spring 예외 안에 감싸진 Hibernate 예외에서 DB 제약 이름을 확인한다.
            if (throwable instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();

                boolean isNameDuplicated = constraintName != null
                        && (constraintName.equals("uk_attraction_name_active")
                        || constraintName.endsWith(".uk_attraction_name_active")
                );
                if (isNameDuplicated) {
                    return true;
                }
            }
            // 현재 예외에서 찾지 못했으면 안쪽 원인을 확인한다.
            throwable = throwable.getCause();
        }
        // 원인 예외를 모두 확인해도 이름 중복 제약을 찾지 못한 경우다.
        return false;
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
        return attractionSlotRepository.findReservableSlots(operatingAttractionIds, reservableFrom, todayEnd)
                .stream()
                .collect(Collectors.toMap(
                        ReservableSlotView::attractionId,   // getAttractionId → attractionId
                        Function.identity(),
                        BinaryOperator.minBy(Comparator.comparing(ReservableSlotView::startsAt)) // getStartsAt → startsAt
                ));
    }

    // 어트랙션 상세 조회
    // ★ 처음엔 DB에서 읽고 Redis에 "attractionDetail::{id}" 로 저장, 다음부터는 Redis에서 꺼낸다
    //   (없는 어트랙션이면 예외가 나므로 저장되지 않는다)
    @Cacheable(cacheNames = CacheConfig.ATTRACTION_DETAIL, key = "#attractionId")
    @Transactional(readOnly = true)
    public SearchAttractionResponse findAttractionById(Long attractionId) {
        Attraction attraction = findActiveAttraction(attractionId);
        return SearchAttractionResponse.from(attraction);
    }

    private Attraction findActiveAttraction(Long attractionId) {
        return attractionRepository.findByIdAndDeletedAtIsNull(attractionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND));
    }

    // 어트랙션 수정
    @Transactional
    public SearchAttractionResponse updateAttraction(Long adminId, Long attractionId, UpdateAttractionRequest request) {
        Attraction attraction = findActiveAttractionForUpdate(attractionId);

        // 추가: 바꾸기 전 소요 시간 보관
        Integer previousDurationMinutes = attraction.getDurationMinutes();

        boolean isNameChanged = request.name() != null && !request.name().equals(attraction.getName());
        if (isNameChanged
                && attractionRepository.existsByNameAndIdNotAndDeletedAtIsNull(request.name(), attractionId)) {
            throw new BusinessException(ErrorCode.ATTRACTION_NAME_DUPLICATED);
        }

        // 소요 시간을 생략하거나 기존 값과 동일하게 요청하면 슬롯 검사를 수행하지 않는다.
        boolean isDurationChanged = request.durationMinutes() != null && !request.durationMinutes().equals(previousDurationMinutes);
        // 어트랙션 잠금 확보 후의 KST를 기준으로 취소되지 않은 운영 예정·진행 중 슬롯을 확인한다.
        // 기존 슬롯·예약 시간표를 보존하기 위해 예약 인원과 관계없이 소요 시간 변경을 거부한다.
        if (isDurationChanged &&
                attractionSlotRepository.existsByAttraction_IdAndStatusNotAndEndsAtAfter(attractionId, SlotStatus.CANCELLED, KstTime.now())) {
            // 엔티티 수정 전에 거부하여 함께 요청한 이름·설명 등도 변경되지 않도록 한다.
            throw new BusinessException(ErrorCode.ATTRACTION_DURATION_CHANGE_NOT_ALLOWED);
        }

        attraction.update(
                request.name(),
                request.description(),
                request.location(),
                request.durationMinutes(),
                request.entranceMoveMinutes() // 추가
        );
        // updatedAt 은 DB에 반영(flush)될 때 채워지므로, 응답 전에 먼저 반영한다.
        try {
            attractionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (isAttractionNameDuplicate(e)) {
                throw new BusinessException(ErrorCode.ATTRACTION_NAME_DUPLICATED);
            }
            throw e;
        }

        // 커밋이 끝난 뒤 상세 캐시 삭제 (예외로 롤백되면 실행되지 않음)
        attractionDetailCacheEvictor.evictAfterCommit(attractionId);

        // [로그 수정] 커밋 뒤에 남긴다.
        Integer currentDurationMinutes = attraction.getDurationMinutes();
        AfterCommit.run(() -> log.info("event=ATTRACTION_UPDATED attractionId={} userId={} previousDurationMinutes={} currentDurationMinutes={}",
                attractionId, adminId, previousDurationMinutes, currentDurationMinutes));

        return SearchAttractionResponse.from(attraction);
    }

    // 어트랙션 운영 상태 변경
    @Transactional
    public UpdateAttractionStatusResponse updateAttractionStatus(
            Long adminId,
            Long attractionId,
            UpdateAttractionStatusRequest request
    ) {
        OperationStatus newStatus = OperationStatus.from(request.operationStatus());
        Attraction attraction = findActiveAttractionForUpdate(attractionId);
        OperationStatus previousStatus = attraction.getOperationStatus();

        if (previousStatus == newStatus) {
            throw new BusinessException(ErrorCode.ATTRACTION_STATUS_SAME);
        }
        // 운영중이 아닌 상태로 바꿀 때만 이용 예정 예약 확인 (BR-ATR-05)
        if (newStatus != OperationStatus.OPERATING) {
            validateNoUpcomingReservations(attractionId, "상태를 변경");
        }

        attraction.changeOperationStatus(newStatus);
        attractionRepository.flush();

        // 커밋이 끝난 뒤 상세 캐시 삭제
        attractionDetailCacheEvictor.evictAfterCommit(attractionId);

        // [로그 수정] 커밋 뒤에 남기고, enum은 name()으로 넘긴다.
        String previousStatusName = previousStatus.name();
        String currentStatusName = newStatus.name();
        AfterCommit.run(() -> log.info("event=ATTRACTION_STATUS_CHANGED attractionId={} userId={} previousStatus={} currentStatus={}",
                attractionId, adminId, previousStatusName, currentStatusName));

        return UpdateAttractionStatusResponse.of(attraction, previousStatus);
    }

    private void validateNoUpcomingReservations(Long attractionId, String actionName) {
        long upcomingCount = upcomingReservationReader
                .countUpcomingReservationsByAttractionId(attractionId, KstTime.now());
        if (upcomingCount > 0) {
            String message = String.format(
                    "이용 예정 예약이 %d건 있어 %s할 수 없습니다. 예약을 먼저 처리해 주세요.",
                    upcomingCount, actionName);
            throw new BusinessException(ErrorCode.ATTRACTION_HAS_UPCOMING_RESERVATIONS, message);
        }
    }

    // 어트랙션 삭제
    @Transactional
    public void deleteAttraction(Long adminId, Long attractionId) {
        Attraction attraction = findActiveAttractionForUpdate(attractionId);
        validateNoUpcomingReservations(attractionId, "삭제");
        attraction.deleted();

        attractionDetailCacheEvictor.evictAfterCommit(attractionId);

        // [로그 수정] 커밋 뒤에 남긴다.
        AfterCommit.run(() -> log.info("event=ATTRACTION_DELETED attractionId={} userId={}", attractionId, adminId));
    }

    // 예약 - 잠금 전용 조회 메서드
    private Attraction findActiveAttractionForUpdate(Long attractionId) {
        return attractionRepository.findActiveByIdForUpdate(attractionId).orElseThrow(
                () -> new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND)
        );
    }
}