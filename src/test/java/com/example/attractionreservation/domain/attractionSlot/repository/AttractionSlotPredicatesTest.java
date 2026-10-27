package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.attractionreservation.domain.attractionSlot.entity.QAttractionSlot.attractionSlot;
import static com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotPredicates.isReservableSlot;
import static org.assertj.core.api.Assertions.assertThat;

// AttractionSlotPredicates.isReservableSlot() 테스트
// 조건 4개(OPEN / 잔여 1 이상 / 마감 전 / 오늘 안)를 "하나씩만 어긴 슬롯"을 만들어서
// 정확히 그 슬롯만 빠지는지 확인한다.
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class AttractionSlotPredicatesTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2030, 1, 1, 0, 0);
    private static final LocalDateTime RESERVABLE_FROM = DAY.withHour(12); // 12:00 이후 시작만 예약 가능
    private static final LocalDateTime TODAY_END = DAY.plusDays(1);         // 다음 날 0시 전까지

    @Autowired
    private JPAQueryFactory queryFactory;

    @Autowired
    private AttractionRepository attractionRepository;

    @Autowired
    private AttractionSlotRepository attractionSlotRepository;

    private Attraction attraction;

    @BeforeEach
    void setUp() {
        attraction = attractionRepository.save(
                Attraction.create("롤러코스터", "빠른 코스터", "A구역", 10, 5));
    }

    // 조건을 그대로 where 에 넣어서, 통과한 슬롯의 "시작 시각"만 꺼낸다.
    private List<LocalDateTime> findStartsAtMatchingPredicate() {
        return queryFactory
                .select(attractionSlot.startsAt)
                .from(attractionSlot)
                .where(isReservableSlot(RESERVABLE_FROM, TODAY_END))
                .orderBy(attractionSlot.startsAt.asc())
                .fetch();
    }

    private AttractionSlot saveSlot(LocalDateTime startsAt, int capacity) {
        return attractionSlotRepository.saveAndFlush(AttractionSlot.create(attraction, startsAt, capacity));
    }

    @Test
    @DisplayName("OPEN 상태이고 잔여 정원이 있으며 마감 전인 오늘 슬롯은 조건을 통과한다")
    void isReservableSlot_AllConditionsMet_Included() {
        // given
        saveSlot(DAY.withHour(14), 10);

        // when
        List<LocalDateTime> result = findStartsAtMatchingPredicate();

        // then
        assertThat(result).containsExactly(DAY.withHour(14));
    }

    @Test
    @DisplayName("OPEN이 아닌 슬롯(CLOSED, SUSPENDED, CANCELLED)은 제외한다")
    void isReservableSlot_NotOpenStatus_Excluded() {
        // given
        AttractionSlot closed = AttractionSlot.create(attraction, DAY.withHour(13), 10);
        closed.changeStatus(SlotStatus.CLOSED);

        AttractionSlot suspended = AttractionSlot.create(attraction, DAY.withHour(14), 10);
        suspended.changeStatus(SlotStatus.SUSPENDED);

        AttractionSlot cancelled = AttractionSlot.create(attraction, DAY.withHour(15), 10);
        cancelled.cancel();

        attractionSlotRepository.saveAllAndFlush(List.of(closed, suspended, cancelled));

        // when
        List<LocalDateTime> result = findStartsAtMatchingPredicate();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("잔여 정원이 0인 슬롯은 제외하고, 1 남은 슬롯은 포함한다")
    void isReservableSlot_RemainingCount_ExcludesZeroIncludesOne() {
        // given
        AttractionSlot full = AttractionSlot.create(attraction, DAY.withHour(13), 5);
        ReflectionTestUtils.setField(full, "reservedCount", 5); // 잔여 0

        AttractionSlot oneLeft = AttractionSlot.create(attraction, DAY.withHour(14), 5);
        ReflectionTestUtils.setField(oneLeft, "reservedCount", 4); // 잔여 1

        attractionSlotRepository.saveAllAndFlush(List.of(full, oneLeft));

        // when
        List<LocalDateTime> result = findStartsAtMatchingPredicate();

        // then
        assertThat(result).containsExactly(DAY.withHour(14));
    }

    @Test
    @DisplayName("예약 가능 시작 시각 직전에 시작하는 슬롯은 제외하고, 딱 그 시각에 시작하면 포함한다")
    void isReservableSlot_ReservableFromBoundary_IncludesEqual() {
        // given
        saveSlot(RESERVABLE_FROM.minusMinutes(1), 10); // 11:59 → 이미 마감
        saveSlot(RESERVABLE_FROM, 10);                 // 12:00 → 포함 (goe: 크거나 같음)

        // when
        List<LocalDateTime> result = findStartsAtMatchingPredicate();

        // then
        assertThat(result).containsExactly(RESERVABLE_FROM);
    }

    @Test
    @DisplayName("오늘 끝 시각 직전에 시작하면 포함하고, 딱 그 시각(다음 날 0시)에 시작하면 제외한다")
    void isReservableSlot_TodayEndBoundary_ExcludesEqual() {
        // given
        saveSlot(TODAY_END.minusMinutes(1), 10); // 23:59 → 포함
        saveSlot(TODAY_END, 10);                 // 다음 날 00:00 → 제외 (lt: 작다)

        // when
        List<LocalDateTime> result = findStartsAtMatchingPredicate();

        // then
        assertThat(result).containsExactly(TODAY_END.minusMinutes(1));
    }
}
