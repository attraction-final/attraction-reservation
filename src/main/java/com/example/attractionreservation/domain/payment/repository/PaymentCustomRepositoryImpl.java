package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static com.example.attractionreservation.domain.payment.entity.QPayment.payment;

@Repository
@RequiredArgsConstructor
public class PaymentCustomRepositoryImpl implements PaymentCustomRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<PaymentResponse> searchAllByUserId(Long userId, Pageable pageable,
                                                   SearchPaymentCondition condition) {
        List<PaymentResponse> list = queryFactory
                .select(Projections.constructor(PaymentResponse.class,
                        payment.id,
                        payment.amount,
                        payment.status,
                        payment.paidAt
                        ))
                .from(payment)
                .where(allConditions(userId, condition))
                .orderBy(orderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory.select(payment.count())
                .from(payment)
                .where(allConditions(userId, condition))
                .fetchOne();

        return new PageImpl<>(
                list,
                pageable,
                total != null ? total : 0
        );
    }

    private BooleanBuilder allConditions(Long userId, SearchPaymentCondition condition) {
        BooleanBuilder builder = new BooleanBuilder();

        return builder
                .and(userIdEq(userId))
                .and(statusEquals(condition.paymentStatus()));
    }

    private BooleanExpression userIdEq(Long userId) {
        return payment.order.user.id.eq(userId);
    }

    private BooleanExpression statusEquals(PaymentStatus status) {
        return status != null ? payment.status.eq(status) : null;
    }

    // 정렬 조건이 없으면 최신순, 허용된 필드(createdAt, amount, paidAt) 외에는 예외
    private OrderSpecifier<?>[] orderSpecifiers(Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();

        for (Sort.Order order : sort) {
            Order direction = order.isAscending() ? Order.ASC : Order.DESC;

            switch (order.getProperty()) {
                case "createdAt" -> orders.add(new OrderSpecifier<>(direction, payment.createdAt));
                case "amount" -> orders.add(new OrderSpecifier<>(direction, payment.amount));
                case "paidAt" -> orders.add(new OrderSpecifier<>(direction, payment.paidAt));
                default -> throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY);
            }
        }

        if (orders.isEmpty()) {
            orders.add(payment.createdAt.desc());
        }

        return orders.toArray(OrderSpecifier[]::new);
    }
}
