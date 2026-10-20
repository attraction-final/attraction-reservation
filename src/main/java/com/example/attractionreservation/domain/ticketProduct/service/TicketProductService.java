package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.domain.ticketProduct.dto.*;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketProductService {

    private final TicketProductRepository ticketProductRepository;

    // 티켓 상품 등록
    // TODO: 추후 관리자 인증 검증 부분 추가 예정
    @Transactional
    public CreateTicketProductResponse createTicketProduct(CreateTicketProductRequest request) {
        TicketProduct ticketProduct = new TicketProduct(
                request.name(), request.price(), request.description(),
                request.type(), request.stock(), request.saleStatus()
        );

        TicketProduct savedTicketProduct = ticketProductRepository.save(ticketProduct);

        return CreateTicketProductResponse.from(savedTicketProduct);
    }

    // 티켓 상품 수정
    // TODO: 추후 관리자 인증 검증 부분 추가 예정
    @Transactional
    public UpdateTicketProductResponse updateTicketProduct(Long ticketProductId, @Valid UpdateTicketProductRequest request) {
        TicketProduct ticketProduct = ticketProductRepository.findById(ticketProductId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND));

        ticketProduct.updateTicketProduct(
                request.name(),
                request.price(),
                request.description(),
                request.stock(),
                request.saleStatus()
        );

        return UpdateTicketProductResponse.from(ticketProduct);
    }

    // 티켓 상품 목록 조회
    public Page<SearchTicketProductResponse> getTicketProductAll(SearchTicketProductCondition condition, Pageable pageable) {
        Page<TicketProduct> ticketProducts = ticketProductRepository.findAllByCondition(
                condition.name(),
                condition.type(),
                condition.status(),
                pageable);

        return ticketProducts.map(SearchTicketProductResponse::from);
    }
}
