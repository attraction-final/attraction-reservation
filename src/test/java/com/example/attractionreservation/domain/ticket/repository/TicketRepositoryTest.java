package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketCondition;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketResponse;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class TicketRepositoryTest {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long emptyUserId;

    private Long firstTicketId;
    private Long secondTicketId;
    private Long thirdTicketId;
    private Long otherUserTicketId;

    @BeforeEach
    void setUp() {
        User user = createUser("owner@test.com");
        User otherUser = createUser("other@test.com");
        User emptyUser = createUser("empty@test.com");

        userId = user.getId();
        emptyUserId = emptyUser.getId();

        firstTicketId = createTicket(
                user,
                "종일 기본 이용권",
                TicketType.ALL_DAY,
                TicketStatus.UNUSED
        ).getId();

        secondTicketId = createTicket(
                user,
                "종일 특별 이용권",
                TicketType.ALL_DAY,
                TicketStatus.USED
        ).getId();

        thirdTicketId = createTicket(
                user,
                "오후 이용권",
                TicketType.AFTERNOON,
                TicketStatus.UNUSED
        ).getId();

        // 첫 번째 사용자 티켓과 검색 조건이 같아도 제외되어야 한다.
        otherUserTicketId = createTicket(
                otherUser,
                "종일 기본 이용권",
                TicketType.ALL_DAY,
                TicketStatus.UNUSED
        ).getId();

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("검색 조건이 없으면 본인 소유 티켓만 반환한다")
    void findAllByUserIdWithCondition_NoConditions_OnlyOwnTickets() {
        // when
        Page<Ticket> result = search(
                userId, null, null, null,
                PageRequest.of(0, 10)
        );

        // then
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent())
                .extracting(Ticket::getId)
                .containsExactlyInAnyOrder(
                        firstTicketId,
                        secondTicketId,
                        thirdTicketId
                )
                .doesNotContain(otherUserTicketId);
    }

    @Test
    @DisplayName("본인 티켓 이름을 부분 일치로 검색한다")
    void findAllByUserIdWithCondition_Name_ReturnsMatchingTickets() {
        // when
        Page<Ticket> result = search(
                userId, "종일", null, null,
                PageRequest.of(0, 10)
        );

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(Ticket::getId)
                .containsExactlyInAnyOrder(
                        firstTicketId,
                        secondTicketId
                );
    }

    @Test
    @DisplayName("티켓 종류로 검색한다")
    void findAllByUserIdWithCondition_Type_ReturnsMatchingTickets() {
        // when
        Page<Ticket> result = search(
                userId, null, TicketType.AFTERNOON, null,
                PageRequest.of(0, 10)
        );

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(Ticket::getId)
                .containsExactly(thirdTicketId);
    }

    @Test
    @DisplayName("티켓 상태로 검색한다")
    void findAllByUserIdWithCondition_Status_ReturnsMatchingTickets() {
        // when
        Page<Ticket> result = search(
                userId, null, null, TicketStatus.UNUSED,
                PageRequest.of(0, 10)
        );

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(Ticket::getId)
                .containsExactlyInAnyOrder(
                        firstTicketId,
                        thirdTicketId
                );
    }

    @Test
    @DisplayName("모든 조건이 일치해도 다른 사용자의 티켓은 제외한다")
    void findAllByUserIdWithCondition_AllConditions_OnlyOwnTickets() {
        // when
        Page<Ticket> result = search(
                userId,
                "종일",
                TicketType.ALL_DAY,
                TicketStatus.UNUSED,
                PageRequest.of(0, 10)
        );

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(Ticket::getId)
                .containsExactly(firstTicketId);
    }

    @Test
    @DisplayName("다른 사용자에게만 티켓이 있으면 빈 페이지를 반환한다")
    void findAllByUserIdWithCondition_NoOwnTickets_EmptyPage() {
        // when
        Page<Ticket> result = search(
                emptyUserId, null, null, null,
                PageRequest.of(0, 10)
        );

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("검색에 일치하는 티켓이 없으면 빈 페이지를 반환한다")
    void findAllByUserIdWithCondition_NoMatch_EmptyPage() {
        // when
        Page<Ticket> result = search(
                userId, "존재하지않는이름", null, null,
                PageRequest.of(0, 10)
        );

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("첫 페이지와 마지막 페이지의 데이터 및 전체 건수가 정확하다")
    void findAllByUserIdWithCondition_Pagination_CorrectResults() {
        // given
        Sort sort = Sort.by(Sort.Direction.ASC, "id");

        // when
        Page<Ticket> firstPage = search(
                userId, null, null, null,
                PageRequest.of(0, 2, sort)
        );

        Page<Ticket> lastPage = search(
                userId, null, null, null,
                PageRequest.of(1, 2, sort)
        );

        // then
        assertThat(firstPage.getContent())
                .extracting(Ticket::getId)
                .containsExactly(firstTicketId, secondTicketId);
        assertThat(firstPage.getNumber()).isZero();
        assertThat(firstPage.getSize()).isEqualTo(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(lastPage.getContent())
                .extracting(Ticket::getId)
                .containsExactly(thirdTicketId);
        assertThat(lastPage.getNumber()).isEqualTo(1);
        assertThat(lastPage.getTotalElements()).isEqualTo(3);
        assertThat(lastPage.getTotalPages()).isEqualTo(2);
        assertThat(lastPage.hasNext()).isFalse();
        assertThat(lastPage.isLast()).isTrue();
    }

    @Test
    @DisplayName("다른 사용자의 티켓 ID로 상세 조회하면 빈 결과를 반환한다")
    void findByIdAndOwnerId_OtherUserTicket_ReturnsEmpty() {
        // given
        Long requestedTicketId = otherUserTicketId;
        Long requestingUserId = userId;

        // when
        var result = ticketRepository.findByIdAndOwner_Id(
                requestedTicketId,
                requestingUserId
        );

        // then
        assertThat(ticketRepository.findById(requestedTicketId)).isPresent();
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("실제 Repository로 내 티켓을 조회하고 응답 DTO로 변환한다")
    void findTicketAll_RealRepository_ReturnsResponseDtos() {
        // given
        TicketService ticketService = new TicketService(ticketRepository);
        SearchTicketCondition condition =
                new SearchTicketCondition(null, null, null);
        Pageable pageable = PageRequest.of(
                0, 10, Sort.by(Sort.Direction.ASC, "id")
        );

        // when
        Page<SearchTicketResponse> result =
                ticketService.findTicketAll(userId, condition, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent())
                .extracting(SearchTicketResponse::ticketId)
                .containsExactly(
                        firstTicketId,
                        secondTicketId,
                        thirdTicketId
                );
        assertThat(result.getContent())
                .extracting(SearchTicketResponse::name)
                .containsExactly(
                        "종일 기본 이용권",
                        "종일 특별 이용권",
                        "오후 이용권"
                );
    }

    private Page<Ticket> search(
            Long ownerId,
            String name,
            TicketType type,
            TicketStatus status,
            Pageable pageable
    ) {
        return ticketRepository.findAllByUserIdWithCondition(
                ownerId, name, type, status, pageable
        );
    }

    private User createUser(String email) {
        User user = User.builder()
                .email(email)
                .password("test-password")
                .nickname("테스트회원")
                .build();

        entityManager.persist(user);
        return user;
    }

    private Ticket createTicket(
            User user,
            String name,
            TicketType type,
            TicketStatus status
    ) {
        TicketProduct product = new TicketProduct(
                name,
                50000,
                "테스트 상품 설명",
                type,
                100,
                TicketProductStatus.ON_SALE
        );
        entityManager.persist(product);

        Order order = Order.create(user, 50000);
        entityManager.persist(order);

        OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(orderItem, "order", order);
        ReflectionTestUtils.setField(orderItem, "ticketProduct", product);
        ReflectionTestUtils.setField(orderItem, "price", 50000);
        ReflectionTestUtils.setField(orderItem, "quantity", 1);
        entityManager.persist(orderItem);

        Ticket ticket = new Ticket(
                LocalDate.of(2026, 10, 25),
                orderItem
        );

        // 상태 변경 API가 아직 없으므로 테스트 데이터에만 사용한다.
        ReflectionTestUtils.setField(ticket, "status", status);
        entityManager.persist(ticket);

        return ticket;
    }
}
