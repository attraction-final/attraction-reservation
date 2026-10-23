package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.example.attractionreservation.domain.attraction.entity.QAttraction.attraction;
import static com.example.attractionreservation.domain.attractionSlot.entity.QAttractionSlot.attractionSlot;
import static com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotPredicates.isReservableSlot;

// 클래스 이름은 반드시 "Repository 이름 + Impl" (AttractionRepository + Impl)
// 그래야 Spring Data JPA가 자동으로 찾아서 AttractionRepository에 붙여 준다.
@RequiredArgsConstructor
public class AttractionRepositoryImpl implements AttractionRepositoryCustom {

    private static final String SORT_LATEST = "LATEST";

    private final JPAQueryFactory queryFactory;

    // 어트랙션 목록 조회
    @Override
    public Page<Attraction> searchAttractions(String keyword,
                                              boolean operatingOnly,
                                              boolean reservableOnly,
                                              String sort,
                                              LocalDateTime reservableFrom,
                                              LocalDateTime todayEnd,
                                              Pageable pageable) {
        // 1. 목록(내용) 조회
        List<Attraction> content = queryFactory
                .selectFrom(attraction)
                .where(
                        attraction.deletedAt.isNull(),       // 삭제되지 않은 것만
                        nameContains(keyword),               // null 이면 조건 무시
                        operatingOnly(operatingOnly),        // null 이면 조건 무시
                        reservableOnly(reservableOnly, reservableFrom, todayEnd)
                )
                .orderBy(createOrderSpecifiers(sort))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        // 2. 전체 개수 조회 (where 조건은 위와 똑같이 재사용)
        Long total = queryFactory
                .select(attraction.count())
                .from(attraction)
                .where(
                        attraction.deletedAt.isNull(),
                        nameContains(keyword),
                        operatingOnly(operatingOnly),
                        reservableOnly(reservableOnly, reservableFrom, todayEnd)
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total == null ? 0L : total);
    }

    // ===== 조건 조각들 (BooleanExpression) =====
    // null 을 돌려주면 QueryDSL이 where 절에서 그 조건을 "없는 것"으로 처리한다.

    // 이름에 검색어가 포함되는지
    private BooleanExpression nameContains(String keyword) {
        return keyword == null ? null : attraction.name.contains(keyword);
    }

    // 운영중만 보기
    private BooleanExpression operatingOnly(boolean operatingOnly) {
        return operatingOnly ? attraction.operationStatus.eq(OperationStatus.OPERATING) : null;
    }

    // 예약 가능만 보기 = 운영중 + 오늘 예약 가능한 슬롯이 1개 이상 있음
    private BooleanExpression reservableOnly(boolean reservableOnly,
                                             LocalDateTime reservableFrom,
                                             LocalDateTime todayEnd) {
        if (!reservableOnly) {
            return null;
        }
        return attraction.operationStatus.eq(OperationStatus.OPERATING)
                .and(JPAExpressions
                        .selectOne()
                        .from(attractionSlot)
                        .where(
                                attractionSlot.attraction.id.eq(attraction.id),
                                isReservableSlot(reservableFrom, todayEnd)
                        )
                        .exists());
    }

    // 정렬: LATEST(최신순)이면 등록일 내림차순을 맨 앞에, 그다음 이름순 → id순
    private OrderSpecifier<?>[] createOrderSpecifiers(String sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        if (SORT_LATEST.equals(sort)) {
            orders.add(attraction.createdAt.desc());
        }
        orders.add(attraction.name.asc());
        orders.add(attraction.id.asc());
        return orders.toArray(new OrderSpecifier[0]);
    }
}
