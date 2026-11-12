package com.example.attractionreservation.domain.ticketProduct.repository;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

import static com.example.attractionreservation.domain.ticketProduct.entity.QTicketProduct.ticketProduct;

@RequiredArgsConstructor
public class TicketProductCustomRepositoryImpl implements TicketProductCustomRepository{

    private final JPAQueryFactory queryFactory;

    // 내 티켓 목록 조회
    @Override
    public Page<TicketProduct> findAllByCondition(
            String name,
            TicketType type,
            TicketProductStatus status,
            Pageable pageable
    ) {
        List<TicketProduct> content = queryFactory
                .selectFrom(ticketProduct)
                .where(
                        notDeleted(),
                        nameLike(name),
                        typeEq(type),
                        statusEq(status)
                )
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(ticketProduct.count())
                .from(ticketProduct)
                .where(
                        notDeleted(),
                        nameLike(name),
                        typeEq(type),
                        statusEq(status)
                )
                .fetchOne();

        return new PageImpl<>(
                content,
                pageable,
                total != null ? total : 0L
        );
    }

    private BooleanExpression notDeleted() {
        return ticketProduct.status.ne(TicketProductStatus.DELETED);
    }

    private BooleanExpression nameLike(String name) {
        if (name == null) {
            return null;
        }

        return ticketProduct.name.like("%" + name + "%");
    }

    private BooleanExpression typeEq(TicketType type) {
        if (type == null) {
            return null;
        }

        return ticketProduct.type.eq(type);
    }

    private BooleanExpression statusEq(TicketProductStatus status) {
        if (status == null) {
            return null;
        }

        return ticketProduct.status.eq(status);
    }

    private OrderSpecifier<?> toOrderSpecifier(Sort.Order sortOrder) {
        Order direction = sortOrder.isAscending() ? Order.ASC : Order.DESC;

        return switch (sortOrder.getProperty()) {
            case "id" ->
                    new OrderSpecifier<>(direction, ticketProduct.id);
            case "name" ->
                    new OrderSpecifier<>(direction, ticketProduct.name);
            case "type" ->
                    new OrderSpecifier<>(direction, ticketProduct.type);
            case "price" ->
                    new OrderSpecifier<>(direction, ticketProduct.price);
            case "description" ->
                    new OrderSpecifier<>(direction, ticketProduct.description);
            case "status" ->
                    new OrderSpecifier<>(direction, ticketProduct.status);
            case "stock" ->
                    new OrderSpecifier<>(direction, ticketProduct.stock);
            case "createdAt" ->
                    new OrderSpecifier<>(direction, ticketProduct.createdAt);
            case "updatedAt" ->
                    new OrderSpecifier<>(direction, ticketProduct.updatedAt);
            default ->
                    throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY);
        };
    }

    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();

        for (Sort.Order sortOrder : sort) {
            orders.add(toOrderSpecifier(sortOrder));
        }

        return orders.toArray(new OrderSpecifier<?>[0]);
    }
}
