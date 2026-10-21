package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.domain.ticketProduct.dto.SearchTicketProductCondition;
import com.example.attractionreservation.domain.ticketProduct.dto.SearchTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketProductSearchServiceTest {

    @Mock
    private TicketProductRepository ticketProductRepository;

    @InjectMocks
    private TicketProductService ticketProductService;

    @Test
    @DisplayName("검색 조건을 전달하고 상품을 응답 DTO로 변환한다")
    void getTicketProductAll_WithConditions_Success() {
        // given
        SearchTicketProductCondition condition =
                new SearchTicketProductCondition(
                        "종일",
                        TicketType.ALL_DAY,
                        TicketProductStatus.ON_SALE
                );

        Pageable pageable = PageRequest.of(
                1, 2, Sort.by(Sort.Direction.ASC, "name")
        );

        TicketProduct product = new TicketProduct(
                "종일이용권",
                50000,
                "테스트 상품 설명",
                TicketType.ALL_DAY,
                100,
                TicketProductStatus.ON_SALE
        );
        ReflectionTestUtils.setField(product, "id", 10L);

        Page<TicketProduct> products =
                new PageImpl<>(List.of(product), pageable, 3);

        when(ticketProductRepository.findAllByCondition(
                condition.name(),
                condition.type(),
                condition.status(),
                pageable
        )).thenReturn(products);

        // when
        Page<SearchTicketProductResponse> result =
                ticketProductService.getTicketProductAll(condition, pageable);

        // then
        assertThat(result.getContent()).containsExactly(
                new SearchTicketProductResponse(
                        10L,
                        "종일이용권",
                        TicketType.ALL_DAY,
                        50000,
                        "테스트 상품 설명",
                        TicketProductStatus.ON_SALE
                )
        );
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getSort()).isEqualTo(pageable.getSort());

        verify(ticketProductRepository).findAllByCondition(
                condition.name(),
                condition.type(),
                condition.status(),
                pageable
        );
    }

    @Test
    @DisplayName("조건 없이 조회한 결과가 없으면 빈 페이지를 반환한다")
    void getTicketProductAll_NoResults_EmptyPage() {
        // given
        SearchTicketProductCondition condition =
                new SearchTicketProductCondition(null, null, null);
        Pageable pageable = PageRequest.of(0, 10);

        when(ticketProductRepository.findAllByCondition(
                null, null, null, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        Page<SearchTicketProductResponse> result =
                ticketProductService.getTicketProductAll(condition, pageable);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(10);

        verify(ticketProductRepository).findAllByCondition(
                null, null, null, pageable
        );
    }
}