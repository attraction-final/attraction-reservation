package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import({TestcontainersConfiguration.class, JpaConfig.class, QuerydslConfig.class})
class PaymentCustomRepositoryImplTest {
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TestEntityManager em;

    private final Pageable pageable = PageRequest.of(0, 10);
    private User user;
    private User otherUser;

    @BeforeEach
    void setUp() {
        user = em.persist(createUser("user@email.com"));
        otherUser = em.persist(createUser("other@email.com"));
    }

    @Test
    @DisplayName("다른 사용자의 결제는 조회되지 않는다.")
    void searchAllByUserId_otherUsersPayments_ExcludesThem() {
        //given
        Payment myPayment = savePayment(user);
        savePayment(otherUser);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), pageable, new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::paymentId)
                .containsExactly(myPayment.getId());
    }

    @Test
    @DisplayName("결제 상태 조건이 있으면 해당 상태의 결제만 조회된다.")
    void searchAllByUserId_statusCondition_ReturnsMatchingOnly() {
        //given
        Payment paid = savePayment(user);
        paid.markAsPaid();
        savePayment(user);
        em.flush();

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), pageable, new SearchPaymentCondition(PaymentStatus.PAID));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::paymentId, PaymentResponse::status)
                .containsExactly(tuple(paid.getId(), PaymentStatus.PAID));
    }

    @Test
    @DisplayName("결제 상태 조건이 없으면 모든 상태의 결제가 조회된다.")
    void searchAllByUserId_nullStatus_ReturnsAll() {
        //given
        Payment paid = savePayment(user);
        paid.markAsPaid();
        savePayment(user);
        em.flush();

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), pageable, new SearchPaymentCondition(null));

        //then
        assertEquals(2, result.getTotalElements());
    }

    @Test
    @DisplayName("페이지 크기만큼 조회되고 전체 개수는 조건에 맞는 결제 수와 같다.")
    void searchAllByUserId_pageSize_ReturnsPagedResultWithTotal() {
        //given
        savePayment(user);
        savePayment(user);
        savePayment(user);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), PageRequest.of(0, 2), new SearchPaymentCondition(null));

        //then
        assertEquals(2, result.getContent().size());
        assertEquals(3, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
    }

    @Test
    @DisplayName("결제 내역이 없으면 빈 페이지를 반환한다.")
    void searchAllByUserId_noPayments_ReturnsEmptyPage() {
        //given
        savePayment(otherUser);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), pageable, new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent()).isEmpty();
        assertEquals(0, result.getTotalElements());
    }

    @Test
    @DisplayName("정렬 조건이 없으면 생성 시각 내림차순으로 조회된다.")
    void searchAllByUserId_noSort_ReturnsCreatedAtDesc() {
        //given
        Payment middle = savePayment(user);
        Payment latest = savePayment(user);
        Payment oldest = savePayment(user);
        updateCreatedAt(middle, Instant.parse("2026-01-02T00:00:00Z"));
        updateCreatedAt(latest, Instant.parse("2026-01-03T00:00:00Z"));
        updateCreatedAt(oldest, Instant.parse("2026-01-01T00:00:00Z"));

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), pageable, new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::paymentId)
                .containsExactly(latest.getId(), middle.getId(), oldest.getId());
    }

    @Test
    @DisplayName("정렬 조건이 없고 생성 시각이 같으면 id 내림차순으로 조회된다.")
    void searchAllByUserId_noSortAndSameCreatedAt_ReturnsIdDesc() {
        //given
        Instant sameCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
        Payment first = savePayment(user);
        Payment second = savePayment(user);
        Payment third = savePayment(user);
        updateCreatedAt(first, sameCreatedAt);
        updateCreatedAt(second, sameCreatedAt);
        updateCreatedAt(third, sameCreatedAt);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), pageable, new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::paymentId)
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    @DisplayName("정렬 기준 값이 같으면 id 내림차순으로 조회된다.")
    void searchAllByUserId_sameSortValue_ReturnsIdDesc() {
        //given
        Payment first = savePayment(user, 10000);
        Payment second = savePayment(user, 10000);
        Payment third = savePayment(user, 10000);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "amount")),
                new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::paymentId)
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    @DisplayName("금액 오름차순 정렬 조건이면 금액이 작은 결제부터 조회된다.")
    void searchAllByUserId_sortByAmountAsc_ReturnsAscending() {
        //given
        savePayment(user, 30000);
        savePayment(user, 10000);
        savePayment(user, 20000);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "amount")),
                new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::amount)
                .containsExactly(10000, 20000, 30000);
    }

    @Test
    @DisplayName("금액 내림차순 정렬 조건이면 금액이 큰 결제부터 조회된다.")
    void searchAllByUserId_sortByAmountDesc_ReturnsDescending() {
        //given
        savePayment(user, 30000);
        savePayment(user, 10000);
        savePayment(user, 20000);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "amount")),
                new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::amount)
                .containsExactly(30000, 20000, 10000);
    }

    @Test
    @DisplayName("정렬 조건이 페이지 단위에도 적용된다.")
    void searchAllByUserId_sortWithPaging_ReturnsSortedPage() {
        //given
        savePayment(user, 30000);
        savePayment(user, 10000);
        savePayment(user, 20000);

        //when
        Page<PaymentResponse> result = paymentRepository.searchAllByUserId(
                user.getId(), PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "amount")),
                new SearchPaymentCondition(null));

        //then
        assertThat(result.getContent())
                .extracting(PaymentResponse::amount)
                .containsExactly(30000);
    }

    @Test
    @DisplayName("허용되지 않은 필드로 정렬하면 예외가 발생한다.")
    void searchAllByUserId_unsupportedSortProperty_ThrowsException() {
        //given
        Pageable sortByPgId = PageRequest.of(0, 10, Sort.by("pgId"));
        SearchPaymentCondition condition = new SearchPaymentCondition(null);

        //when & then
        assertThatThrownBy(() -> paymentRepository.searchAllByUserId(user.getId(), sortByPgId, condition))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_SORT_PROPERTY.getMessage());
    }

    private User createUser(String email) {
        return User.builder()
                .email(email)
                .password("password")
                .nickname("name")
                .build();
    }

    private Payment savePayment(User owner) {
        return savePayment(owner, 10000);
    }

    private Payment savePayment(User owner, int amount) {
        Order order = em.persist(Order.create(owner, amount));
        return em.persist(Payment.create(order));
    }

    // createdAt은 Auditing으로 저장 시점에 채워지므로, 정렬 검증을 위해 DB 값을 직접 변경
    private void updateCreatedAt(Payment payment, Instant createdAt) {
        em.flush();
        em.getEntityManager()
                .createNativeQuery("update payments set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", payment.getId())
                .executeUpdate();
    }
}
