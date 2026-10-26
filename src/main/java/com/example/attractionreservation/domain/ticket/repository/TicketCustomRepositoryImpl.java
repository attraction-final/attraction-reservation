package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
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
import java.util.Optional;

import static com.example.attractionreservation.domain.order.entity.QOrder.order;
import static com.example.attractionreservation.domain.order.entity.QOrderItem.orderItem;
import static com.example.attractionreservation.domain.ticket.entity.QTicket.ticket;

@RequiredArgsConstructor
public class TicketCustomRepositoryImpl implements TicketCustomRepository{

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Ticket> findAllByUserIdWithCondition(
            Long userId,
            String name,
            TicketType type,
            TicketStatus status,
            Pageable stablePageable
    ) {
        List<Ticket> content = queryFactory
                .selectFrom(ticket)
                .join(ticket.orderItem, orderItem)
                .join(orderItem.order, order)
                .where(
                        ownerIdEq(userId),
                        nameLike(name),
                        typeEq(type),
                        statusEq(status)
                )
                .orderBy(toOrderSpecifiers(stablePageable.getSort()))
                .offset(stablePageable.getOffset())
                .limit(stablePageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(ticket.count())
                .from(ticket)
                .join(ticket.orderItem, orderItem)
                .join(orderItem.order, order)
                .where(
                        ownerIdEq(userId),
                        nameLike(name),
                        typeEq(type),
                        statusEq(status)
                )
                .fetchOne();
        return new PageImpl<>(
                content,
                stablePageable,
                total != null ? total : 0L
        );
    }

    // 내 티켓 상세 조회
    @Override
    public Optional<Ticket> findByIdAndOwner_Id(Long ticketId, Long userId) {
        Ticket result = queryFactory
                .selectFrom(ticket)
                .join(ticket.orderItem, orderItem)
                .join(orderItem.order, order)
                .where(
                        ticketIdEq(ticketId),
                        ownerIdEq(userId)
                )
                .fetchOne();
        return Optional.ofNullable(result);
    }

    private BooleanExpression ticketIdEq(Long ticketId) {
        return ticket.id.eq(ticketId);
    }

    private BooleanExpression ownerIdEq(Long userId) {
        return order.user.id.eq(userId);
    }

    private BooleanExpression nameLike(String name) {
        if (name == null) {
            return null;
        }
        return ticket.name.like("%" + name + "%");
    }

    private BooleanExpression typeEq(TicketType type) {
        if (type == null) {
            return null;
        }
        return ticket.type.eq(type);
    }

    private BooleanExpression statusEq(TicketStatus status) {
        if (status == null) {
            return null;
        }
        return ticket.status.eq(status);
    }

    private OrderSpecifier<?> toOrderSpecifier(Sort.Order sortOrder) {
        Order direction = sortOrder.isAscending() ? Order.ASC : Order.DESC;

        return switch (sortOrder.getProperty()) {
            case "id" ->
                    new OrderSpecifier<>(direction, ticket.id);
            case "validDate" ->
                    new OrderSpecifier<>(direction, ticket.validDate);
            case "status" ->
                    new OrderSpecifier<>(direction, ticket.status);
            case "code" ->
                    new OrderSpecifier<>(direction, ticket.code);
            case "name" ->
                    new OrderSpecifier<>(direction, ticket.name);
            case "type" ->
                    new OrderSpecifier<>(direction, ticket.type);
            case "enteredAt" ->
                    new OrderSpecifier<>(direction, ticket.enteredAt);
            case "endTime" ->
                    new OrderSpecifier<>(direction, ticket.endTime);
            case "orderItem" ->
                    new OrderSpecifier<>(direction, orderItem.id);
            case "createdAt" ->
                    new OrderSpecifier<>(direction, ticket.createdAt);
            case "updatedAt" ->
                    new OrderSpecifier<>(direction, ticket.updatedAt);
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
