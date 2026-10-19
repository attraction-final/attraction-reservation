package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketProductService {

    private final TicketProductRepository ticketProductRepository;

    // 티켓 상품 등록
    // TODO: 추후 관리자 인증 검증 부분 추가 예정
    @Transactional
    public CreateTicketProductResponse create(CreateTicketProductRequest request) {
        TicketProduct ticketProduct = new TicketProduct(
                request.name(), request.price(), request.description(),
                request.type(), request.stock(), request.saleStatus()
        );

        TicketProduct savedTicketProduct = ticketProductRepository.save(ticketProduct);

        return CreateTicketProductResponse.from(savedTicketProduct);
    }
}
