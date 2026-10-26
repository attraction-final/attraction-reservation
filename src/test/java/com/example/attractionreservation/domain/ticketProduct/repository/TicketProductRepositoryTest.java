
package com.example.attractionreservation.domain.ticketProduct.repository;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class TicketProductRepositoryTest {

    @Autowired
    private TicketProductRepository ticketProductRepository;

    private TicketType firstType;
    private TicketType secondType;

    @BeforeEach
    void setUp() {
        // 실제 enum에 두 종류가 존재한다는 전제
        TicketType[] types = TicketType.values();
        assertThat(types).hasSizeGreaterThanOrEqualTo(2);

        firstType = types[0];
        secondType = types[1];

        ticketProductRepository.save(
                createProduct("에버랜드 종일권", firstType, TicketProductStatus.ON_SALE)
        );
        ticketProductRepository.save(
                createProduct("롯데월드 종일권", firstType, TicketProductStatus.ON_SALE)
        );
        ticketProductRepository.save(
                createProduct("에버랜드 오후권", secondType, TicketProductStatus.STOPPED)
        );
        ticketProductRepository.save(
                createProduct("삭제된 종일권", firstType, TicketProductStatus.DELETED)
        );
        ticketProductRepository.flush();
    }

    @Test
    @DisplayName("검색 조건이 없으면 삭제되지 않은 상품만 조회한다")
    void findAllByCondition_NoCondition() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        null, null, null, pageable
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent())
                .extracting(TicketProduct::getName)
                .containsExactlyInAnyOrder(
                        "에버랜드 종일권",
                        "롯데월드 종일권",
                        "에버랜드 오후권"
                );
    }

    @Test
    @DisplayName("상품 이름으로 부분 일치 검색할 수 있다")
    void findAllByCondition_Name() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        "에버랜드", null, null, pageable
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(TicketProduct::getName)
                .containsExactlyInAnyOrder(
                        "에버랜드 종일권",
                        "에버랜드 오후권"
                );
    }

    @Test
    @DisplayName("티켓 종류로 상품을 검색할 수 있다")
    void findAllByCondition_Type() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        null, firstType, null, pageable
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .allMatch(product -> product.getType() == firstType);
    }

    @Test
    @DisplayName("판매 상태로 상품을 검색할 수 있다")
    void findAllByCondition_Status() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        null, null, TicketProductStatus.STOPPED, pageable
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getName())
                .isEqualTo("에버랜드 오후권");
    }

    @Test
    @DisplayName("여러 검색 조건을 동시에 적용할 수 있다")
    void findAllByCondition_MultipleConditions() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        "에버랜드",
                        firstType,
                        TicketProductStatus.ON_SALE,
                        pageable
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getName())
                .isEqualTo("에버랜드 종일권");
    }

    @Test
    @DisplayName("DELETED 상품은 검색 조건 없이 조회해도 제외된다")
    void findAllByCondition_ExcludeDeleted() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        null, null, null, pageable
                );

        // then
        assertThat(result.getContent())
                .noneMatch(product ->
                        product.getStatus() == TicketProductStatus.DELETED
                );
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("DELETED 상태로 검색해도 빈 페이지를 반환한다")
    void findAllByCondition_DeletedStatus() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        null, null, TicketProductStatus.DELETED, pageable
                );

        // then
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 페이지를 반환한다")
    void findAllByCondition_NoResult() {
        // given
        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        "존재하지않는상품", null, null, pageable
                );

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("페이지 크기와 전체 데이터 수가 올바르게 적용된다")
    void findAllByCondition_Pagination() {
        // given
        Pageable pageable = PageRequest.of(
                0, 2, Sort.by(Sort.Direction.ASC, "name")
        );

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        null, null, null, pageable
                );

        // then
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getNumber()).isZero();
        assertThat(result.hasNext()).isTrue();
    }

    private TicketProduct createProduct(
            String name,
            TicketType type,
            TicketProductStatus status
    ) {
        // 실제 TicketProduct 생성자 인자 순서에 맞춰 확인 필요
        return new TicketProduct(
                name,
                50000,
                "테스트용 티켓 상품",
                type,
                100,
                status
        );
    }

    @Test
    @DisplayName("마지막 페이지는 남은 상품 1개를 반환하고 다음 페이지가 없다")
    void findAllByCondition_LastPage_ReturnsRemainingProduct() {
        // given
        Pageable pageable = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.ASC, "id")
        );

        // when
        Page<TicketProduct> result =
                ticketProductRepository.findAllByCondition(
                        null, null, null, pageable
                );

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getName())
                .isEqualTo("에버랜드 오후권");
        assertThat(result.getContent())
                .noneMatch(product ->
                        product.getStatus() == TicketProductStatus.DELETED
                );

        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.isLast()).isTrue();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious()).isTrue();
    }
}
