package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.domain.ticketProduct.dto.*;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketProductService {

    private final TicketProductRepository ticketProductRepository;

    // 티켓 상품 등록
    @Transactional
    public CreateTicketProductResponse createTicketProduct(CreateTicketProductRequest request) {
        TicketProduct ticketProduct = new TicketProduct(
                request.name(),
                request.price(),
                request.description(),
                request.type(),
                request.stock(),
                request.saleStatus()
        );

        TicketProduct savedTicketProduct = ticketProductRepository.save(ticketProduct);

        return CreateTicketProductResponse.from(savedTicketProduct);
    }

    // 티켓 상품 수정
    @Transactional
    public UpdateTicketProductResponse updateTicketProduct(Long ticketProductId, UpdateTicketProductRequest request) {
        TicketProduct ticketProduct = getTicketProductById(ticketProductId);

        ticketProduct.updateTicketProduct(
                request.name(),
                request.price(),
                request.description(),
                request.stock(),
                request.saleStatus()
        );

        return UpdateTicketProductResponse.from(ticketProduct);
    }

    // 티켓 상품 삭제
    @Transactional
    public void deleteTicketProduct(Long ticketProductId) {
        TicketProduct ticketProduct = getTicketProductById(ticketProductId);
        ticketProduct.delete();
    }

    // 티켓 상품 목록 조회
    public Page<SearchTicketProductResponse> findTicketProductAll(SearchTicketProductCondition condition, Pageable pageable) {
        Page<TicketProduct> ticketProducts = ticketProductRepository.findAllByCondition(
                condition.name(),
                condition.type(),
                condition.status(),
                pageable);

        return ticketProducts.map(SearchTicketProductResponse::from);
    }

    // 티켓 상품 상세 조회
    public SearchTicketProductResponse findTicketProductById(Long ticketProductId) {
        TicketProduct ticketProduct = getTicketProductById(ticketProductId);
        return SearchTicketProductResponse.from(ticketProduct);
    }

    @Transactional
    public void updateTicketProductsStockIncrease(Map<Long, Integer> quantitiesByTicketProductId) {
        List<TicketProduct> ticketProducts = ticketProductRepository.findAllWithUpdateByIdInOrderById(quantitiesByTicketProductId.keySet());

        if (quantitiesByTicketProductId.size() != ticketProducts.size()) {
            throw new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND);
        }

        ticketProducts.forEach(
                ticketProduct -> ticketProduct.increaseStock(quantitiesByTicketProductId.get(ticketProduct.getId()))
        );
    }

    // 티켓 찾기 중복 코드 메서드 분리
    private TicketProduct getTicketProductById(Long ticketProductId) {
        return ticketProductRepository.findById(ticketProductId).orElseThrow(
                () -> new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND)
        );
    }
}
