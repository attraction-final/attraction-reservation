package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

// QueryDSL로 구현할 메서드 "목록"만 적어 두는 인터페이스
// 실제 내용은 AttractionRepositoryImpl 에 있다.
public interface AttractionRepositoryCustom {

    // 어트랙션 목록 조회 (검색어, 운영중만, 예약 가능만, 정렬, 페이징)
    Page<Attraction> searchAttractions(String keyword,
                                       boolean operatingOnly,
                                       boolean reservableOnly,
                                       String sort,
                                       LocalDateTime reservableFrom,
                                       LocalDateTime todayEnd,
                                       Pageable pageable);
}
