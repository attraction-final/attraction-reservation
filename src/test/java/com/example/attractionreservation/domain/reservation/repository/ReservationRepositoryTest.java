package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class ReservationRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 25);
    private static final LocalDateTime START = DAY.atTime(16, 0);

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EntityManager entityManager;

    private User owner;
    private User otherUser;
    private Ticket ownerTicket;
    private Ticket otherTicket;
    private Attraction attraction;
    private Attraction otherAttraction;

    @BeforeEach
    void setUp() {
        owner = createUser("reservation-owner@test.com");
        otherUser = createUser("reservation-other@test.com");
        ownerTicket = createTicket(owner);
        otherTicket = createTicket(otherUser);
        attraction = Attraction.create("롤러코스터", "테스트 설명", "A구역", 10, 5);
        otherAttraction = Attraction.create("회전목마", "테스트 설명", "B구역", 10, 5);
        entityManager.persist(attraction);
        entityManager.persist(otherAttraction);
    }

    @Test
    @DisplayName("동일 회원과 슬롯의 지정 상태 예약이 있으면 중복으로 판단한다")
    void existsReservedByUserAndSlot_MatchingReservation_ReturnsTrue() {
        // given
        Reservation saved = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Long slotId = saved.getAttractionSlot().getId();
        flushAndClear();

        // when
        boolean result = reservationRepository.existsReservedByUser_IdAndSlot_Id(
                owner.getId(), slotId, ReservationStatus.RESERVED
        );

        // then
        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("동일 슬롯에 다른 회원 또는 비활성 상태 예약만 있으면 중복이 아니다")
    void existsReservedByUserAndSlot_OtherUserOrStatus_ReturnsFalse(ReservationStatus status) {
        // given
        Reservation inactive = createReservation(owner, attraction, START, status);
        AttractionSlot slot = inactive.getAttractionSlot();
        entityManager.persist(new Reservation(otherUser, otherTicket, slot));
        flushAndClear();

        // when
        boolean result = reservationRepository.existsReservedByUser_IdAndSlot_Id(
                owner.getId(), slot.getId(), ReservationStatus.RESERVED
        );

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("같은 회원의 예약이 있어도 슬롯이 다르면 중복이 아니다")
    void existsReservedByUserAndSlot_DifferentSlot_ReturnsFalse() {
        // given
        createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        AttractionSlot emptySlot = AttractionSlot.create(attraction, START.plusHours(1), 20);
        entityManager.persist(emptySlot);
        flushAndClear();

        // when
        boolean result = reservationRepository.existsReservedByUser_IdAndSlot_Id(
                owner.getId(), emptySlot.getId(), ReservationStatus.RESERVED
        );

        // then
        assertThat(result).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "-10, 0, false",
            "10, 20, false",
            "-20, -10, false",
            "20, 30, false",
            "0, 10, true",
            "5, 15, true",
            "-5, 5, true",
            "-5, 15, true",
            "2, 8, true"
    })
    @DisplayName("탑승 시간이 겹칠 때만 충돌이며 시작과 종료가 맞닿으면 충돌이 아니다")
    void existsTimeConflictReservation_TimeBoundaries_ReturnsExpected(
            int startOffset, int endOffset, boolean expected
    ) {
        // given
        createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        boolean result = reservationRepository.existsTimeConflictReservation(
                owner.getId(), START.plusMinutes(startOffset), START.plusMinutes(endOffset),
                ReservationStatus.RESERVED
        );

        // then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("다른 회원 또는 비활성 예약의 탑승 시간이 겹쳐도 충돌이 아니다")
    void existsTimeConflictReservation_OtherUserOrStatus_ReturnsFalse(ReservationStatus status) {
        // given
        createReservation(owner, attraction, START, status);
        createReservation(otherUser, attraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        boolean result = reservationRepository.existsTimeConflictReservation(
                owner.getId(), START, START.plusMinutes(10), ReservationStatus.RESERVED
        );

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("다른 어트랙션 예약이라도 같은 회원의 탑승 시간이 겹치면 충돌이다")
    void existsTimeConflictReservation_DifferentAttraction_ReturnsTrue() {
        // given
        createReservation(owner, otherAttraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        boolean result = reservationRepository.existsTimeConflictReservation(
                owner.getId(), START.plusMinutes(5), START.plusMinutes(15), ReservationStatus.RESERVED
        );

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("조건 없는 내 예약 목록은 다른 회원을 제외하고 모든 상태를 포함한다")
    void findReservationAllByCondition_NoConditions_OnlyOwnReservations() {
        // given
        Reservation first = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Reservation second = createReservation(owner, attraction, START.plusHours(1), ReservationStatus.CANCELLED);
        createReservation(otherUser, attraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        Page<Reservation> result = reservationRepository.findReservationAllByCondition(
                owner.getId(), null, null, null, page(0, 10)
        );

        // then
        assertPage(result, 2, first.getId(), second.getId());
    }

    @Test
    @DisplayName("내 예약 날짜 검색은 당일 자정을 포함하고 이전 날짜와 다음 날 자정을 제외한다")
    void findReservationAllByCondition_DateRange_UsesSlotStartBoundaries() {
        // given
        Reservation midnight = createReservation(owner, attraction, DAY.atStartOfDay(), ReservationStatus.RESERVED);
        Reservation daytime = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        createReservation(owner, attraction, DAY.atStartOfDay().minusMinutes(10), ReservationStatus.RESERVED);
        createReservation(owner, attraction, DAY.plusDays(1).atStartOfDay(), ReservationStatus.RESERVED);
        flushAndClear();

        // when
        Page<Reservation> result = reservationRepository.findReservationAllByCondition(
                owner.getId(), null, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), page(0, 10)
        );

        // then
        assertPage(result, 2, midnight.getId(), daytime.getId());
    }

    @Test
    @DisplayName("내 예약 상태와 날짜를 함께 검색하면 조건에 맞는 전체 건수로 페이징한다")
    void findReservationAllByCondition_FilteredPagination_ReturnsFilteredTotal() {
        // given
        createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Reservation second = createReservation(owner, attraction, START.plusHours(1), ReservationStatus.RESERVED);
        createReservation(owner, attraction, START, ReservationStatus.CANCELLED);
        createReservation(owner, attraction, START.plusDays(1), ReservationStatus.RESERVED);
        createReservation(otherUser, attraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        Page<Reservation> result = reservationRepository.findReservationAllByCondition(
                owner.getId(), ReservationStatus.RESERVED,
                DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), page(1, 1)
        );

        // then
        assertPage(result, 2, second.getId());
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.isLast()).isTrue();
    }

    @Test
    @DisplayName("본인 예약 ID로 상세 조회하면 해당 예약을 반환한다")
    void findReservationByIdAndUserId_OwnReservation_ReturnsReservation() {
        // given
        Reservation saved = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        var result = reservationRepository.findReservationByIdAndUser_Id(saved.getId(), owner.getId());

        // then
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("다른 회원의 예약 또는 존재하지 않는 예약은 상세 조회 결과가 없다")
    void findReservationByIdAndUserId_OtherUserOrMissing_ReturnsEmpty() {
        // given
        Reservation saved = createReservation(otherUser, attraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        var otherResult = reservationRepository.findReservationByIdAndUser_Id(saved.getId(), owner.getId());
        var missingResult = reservationRepository.findReservationByIdAndUser_Id(Long.MAX_VALUE, owner.getId());

        // then
        assertThat(otherResult).isEmpty();
        assertThat(missingResult).isEmpty();
    }

    @Test
    @DisplayName("관리자 목록은 조건이 없으면 모든 회원과 상태의 예약을 조회한다")
    void findAdminReservationByCondition_NoConditions_ReturnsAllReservations() {
        // given
        Reservation first = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Reservation second = createReservation(otherUser, otherAttraction, START, ReservationStatus.CANCELLED);
        flushAndClear();

        // when
        Page<Reservation> result = adminSearch(null, null, null, null, null, 0, 10);

        // then
        assertPage(result, 2, first.getId(), second.getId());
    }

    @ParameterizedTest
    @CsvSource({"USER", "ATTRACTION", "STATUS", "DATE", "ALL"})
    @DisplayName("관리자 목록의 각 선택 조건과 전체 조합이 데이터 및 전체 건수에 동일하게 적용된다")
    void findAdminReservationByCondition_OptionalFilters_ReturnsMatchingReservations(String filter) {
        // given
        Reservation target = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        createReservation(otherUser, otherAttraction, START.plusDays(1), ReservationStatus.CANCELLED);
        boolean all = filter.equals("ALL");
        Long userId = all || filter.equals("USER") ? owner.getId() : null;
        Long attractionId = all || filter.equals("ATTRACTION") ? attraction.getId() : null;
        ReservationStatus status = all || filter.equals("STATUS") ? ReservationStatus.RESERVED : null;
        LocalDateTime startDay = all || filter.equals("DATE") ? DAY.atStartOfDay() : null;
        LocalDateTime nextDay = startDay == null ? null : DAY.plusDays(1).atStartOfDay();
        flushAndClear();

        // when
        Page<Reservation> result = adminSearch(userId, attractionId, status, startDay, nextDay, 0, 10);

        // then
        assertPage(result, 1, target.getId());
    }

    @Test
    @DisplayName("관리자 날짜 검색은 당일 자정을 포함하고 다음 날 자정을 제외한다")
    void findAdminReservationByCondition_DateBoundary_ExcludesNextMidnight() {
        // given
        Reservation target = createReservation(owner, attraction, DAY.atStartOfDay(), ReservationStatus.RESERVED);
        createReservation(otherUser, attraction, DAY.plusDays(1).atStartOfDay(), ReservationStatus.RESERVED);
        createReservation(owner, attraction, DAY.atStartOfDay().minusMinutes(10), ReservationStatus.RESERVED);
        flushAndClear();

        // when
        Page<Reservation> result = adminSearch(
                null, null, null, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), 0, 10
        );

        // then
        assertPage(result, 1, target.getId());
    }

    @Test
    @DisplayName("관리자 검색 조건을 조합하면 다른 조건의 예약을 제외하고 정확한 전체 건수로 페이징한다")
    void findAdminReservationByCondition_CombinedPagination_ReturnsFilteredTotal() {
        // given
        createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Reservation last = createReservation(owner, attraction, START.plusHours(1), ReservationStatus.RESERVED);
        createReservation(otherUser, attraction, START, ReservationStatus.RESERVED);
        createReservation(owner, otherAttraction, START, ReservationStatus.RESERVED);
        createReservation(owner, attraction, START, ReservationStatus.CANCELLED);
        createReservation(owner, attraction, START.plusDays(1), ReservationStatus.RESERVED);
        flushAndClear();

        // when
        Page<Reservation> result = adminSearch(
                owner.getId(), attraction.getId(), ReservationStatus.RESERVED,
                DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), 1, 1
        );

        // then
        assertPage(result, 2, last.getId());
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.isLast()).isTrue();
    }

    @Test
    @DisplayName("검색 조건에 맞는 예약이 없으면 내 목록과 관리자 목록 모두 빈 페이지를 반환한다")
    void findReservationLists_NoMatch_ReturnEmptyPages() {
        // given
        createReservation(otherUser, attraction, START, ReservationStatus.RESERVED);
        flushAndClear();

        // when
        Page<Reservation> own = reservationRepository.findReservationAllByCondition(
                owner.getId(), null, null, null, page(0, 10)
        );
        Page<Reservation> admin = adminSearch(owner.getId(), null, null, null, null, 0, 10);

        // then
        assertPage(own, 0);
        assertPage(admin, 0);
        assertThat(own.getTotalPages()).isZero();
        assertThat(admin.getTotalPages()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Sort.Direction.class)
    @DisplayName("내 목록과 관리자 목록에서 요청한 ID 정렬 방향을 적용한다")
    void findReservationLists_IdSort_AppliesDirection(Sort.Direction direction) {
        // given
        Reservation first = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Reservation second = createReservation(owner, attraction, START.plusHours(1), ReservationStatus.RESERVED);
        PageRequest pageable = PageRequest.of(0, 10, Sort.by(direction, "id"));
        flushAndClear();

        // when
        Page<Reservation> own = reservationRepository.findReservationAllByCondition(
                owner.getId(), null, null, null, pageable
        );
        Page<Reservation> admin = reservationRepository.findAdminReservationByCondition(
                null, null, null, null, null, pageable
        );

        // then
        Long[] expected = direction.isAscending()
                ? new Long[]{first.getId(), second.getId()} : new Long[]{second.getId(), first.getId()};
        assertPage(own, 2, expected);
        assertPage(admin, 2, expected);
    }

    @Test
    @DisplayName("상태 정렬 후 같은 상태의 예약은 ID 보조 정렬을 적용한다")
    void findReservationLists_MultipleSorts_AppliesPriority() {
        // given
        Reservation first = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Reservation second = createReservation(owner, attraction, START.plusHours(1), ReservationStatus.RESERVED);
        Reservation cancelled = createReservation(owner, attraction, START.plusHours(2), ReservationStatus.CANCELLED);
        PageRequest pageable = PageRequest.of(
                0, 10, Sort.by(Sort.Order.asc("status"), Sort.Order.desc("id"))
        );
        flushAndClear();

        // when
        Page<Reservation> own = reservationRepository.findReservationAllByCondition(
                owner.getId(), null, null, null, pageable
        );
        Page<Reservation> admin = reservationRepository.findAdminReservationByCondition(
                null, null, null, null, null, pageable
        );

        // then
        assertPage(own, 3, cancelled.getId(), second.getId(), first.getId());
        assertPage(admin, 3, cancelled.getId(), second.getId(), first.getId());
    }

    @ParameterizedTest
    @CsvSource({
            "userId, user.id",
            "ticketId, ticket.id",
            "slotId, attractionSlot.id"
    })
    @DisplayName("연관 ID 정렬은 별칭과 Entity 경로 이름 모두 같은 결과를 반환한다")
    void findReservationLists_AssociationSortAliases_ReturnSameResults(String alias, String path) {
        // given
        Reservation first = createReservation(owner, attraction, START, ReservationStatus.RESERVED);
        Reservation second = createReservation(owner, attraction, START.plusHours(1), ReservationStatus.RESERVED);
        Reservation other = createReservation(otherUser, otherAttraction, START, ReservationStatus.RESERVED);
        PageRequest aliasPage = PageRequest.of(0, 10, Sort.by(Sort.Order.asc(alias), Sort.Order.asc("id")));
        PageRequest pathPage = PageRequest.of(0, 10, Sort.by(Sort.Order.asc(path), Sort.Order.asc("id")));
        flushAndClear();

        // when
        Page<Reservation> ownAlias = reservationRepository.findReservationAllByCondition(
                owner.getId(), null, null, null, aliasPage
        );
        Page<Reservation> ownPath = reservationRepository.findReservationAllByCondition(
                owner.getId(), null, null, null, pathPage
        );
        Page<Reservation> adminAlias = reservationRepository.findAdminReservationByCondition(
                null, null, null, null, null, aliasPage
        );
        Page<Reservation> adminPath = reservationRepository.findAdminReservationByCondition(
                null, null, null, null, null, pathPage
        );

        // then
        assertPage(ownAlias, 2, first.getId(), second.getId());
        assertPage(ownPath, 2, first.getId(), second.getId());
        assertPage(adminAlias, 3, first.getId(), second.getId(), other.getId());
        assertPage(adminPath, 3, first.getId(), second.getId(), other.getId());
    }

    @Test
    @DisplayName("지원하지 않는 정렬 필드는 내 목록과 관리자 목록에서 예외를 반환한다")
    void findReservationLists_InvalidSort_ThrowsException() {
        // given
        PageRequest pageable = PageRequest.of(0, 10, Sort.by("unknownField"));

        // when & then
        assertThatThrownBy(() -> reservationRepository.findReservationAllByCondition(
                owner.getId(), null, null, null, pageable
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_SORT_PROPERTY));
        assertThatThrownBy(() -> reservationRepository.findAdminReservationByCondition(
                null, null, null, null, null, pageable
        )).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_SORT_PROPERTY));
    }

    private PageRequest page(int number, int size) {
        return PageRequest.of(number, size, Sort.by(Sort.Direction.ASC, "id"));
    }

    private Page<Reservation> adminSearch(
            Long userId, Long attractionId, ReservationStatus status,
            LocalDateTime startDay, LocalDateTime nextDay, int number, int size
    ) {
        return reservationRepository.findAdminReservationByCondition(
                userId, attractionId, status, startDay, nextDay, page(number, size)
        );
    }

    private void assertPage(Page<Reservation> page, long total, Long... ids) {
        assertThat(page.getTotalElements()).isEqualTo(total);
        assertThat(page.getContent()).extracting(Reservation::getId).containsExactly(ids);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Reservation createReservation(
            User user, Attraction target, LocalDateTime startsAt, ReservationStatus status
    ) {
        // 자정 데이터는 날짜 쿼리 경계 검증용이며 Service의 운영 시간 검증은 거치지 않는다.
        AttractionSlot slot = AttractionSlot.create(target, startsAt, 20);
        entityManager.persist(slot);
        Ticket ticket = user.getId().equals(owner.getId()) ? ownerTicket : otherTicket;
        Reservation reservation = new Reservation(user, ticket, slot);
        ReflectionTestUtils.setField(reservation, "status", status);
        entityManager.persist(reservation);
        return reservation;
    }

    private User createUser(String email) {
        User user = User.builder()
                .email(email).password("test-password").nickname("테스트회원").build();
        entityManager.persist(user);
        return user;
    }

    private Ticket createTicket(User user) {
        TicketProduct product = new TicketProduct(
                "종일 이용권", 50000, "테스트 상품 설명", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        entityManager.persist(product);
        Order order = Order.create(user, 50000);
        entityManager.persist(order);
        OrderItem item = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(item, "order", order);
        ReflectionTestUtils.setField(item, "ticketProduct", product);
        ReflectionTestUtils.setField(item, "price", 50000);
        ReflectionTestUtils.setField(item, "quantity", 1);
        entityManager.persist(item);
        Ticket ticket = new Ticket(DAY, item);
        entityManager.persist(ticket);
        return ticket;
    }
}
