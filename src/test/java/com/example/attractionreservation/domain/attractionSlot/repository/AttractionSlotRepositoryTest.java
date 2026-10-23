package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// AttractionSlotRepositoryImpl 테스트
// - countOverlappingSlots / countOverlappingSlotsExcludingSlotId : 시간 겹침 검사
// - findSlotsByAttractionIdAndDate : 하루치 슬롯 조회
// - findReservableSlots : 여러 어트랙션의 예약 가능 슬롯 한 번에 조회
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class AttractionSlotRepositoryTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2030, 1, 1, 0, 0);
    private static final LocalDateTime RESERVABLE_FROM = DAY.withHour(12);
    private static final LocalDateTime TODAY_END = DAY.plusDays(1);

    @Autowired
    private AttractionRepository attractionRepository;

    @Autowired
    private AttractionSlotRepository attractionSlotRepository;

    private Attraction rollerCoaster; // 탑승 시간 10분
    private Attraction viking;        // 다른 어트랙션
    private AttractionSlot morningSlot; // 롤러코스터 11:00 ~ 11:10, OPEN

    @BeforeEach
    void setUp() {
        rollerCoaster = attractionRepository.save(
                Attraction.create("롤러코스터", "빠른 코스터", "A구역", 10, 5));
        viking = attractionRepository.save(
                Attraction.create("바이킹", "흔들리는 배", "B구역", 10, 5));

        morningSlot = AttractionSlot.create(rollerCoaster, DAY.withHour(11), 10);       // 11:00~11:10

        AttractionSlot cancelledSlot = AttractionSlot.create(rollerCoaster, DAY.withHour(13), 10); // 13:00~13:10
        cancelledSlot.cancel();

        AttractionSlot nextDaySlot = AttractionSlot.create(rollerCoaster, DAY.plusDays(1), 10);

        attractionSlotRepository.saveAll(List.of(morningSlot, cancelledSlot, nextDaySlot));
        attractionSlotRepository.flush();
    }

    // ===== countOverlappingSlots (등록용) =====

    @Test
    @DisplayName("기존 슬롯과 시간이 조금이라도 겹치면 1개로 센다")
    void countOverlappingSlots_PartialOverlap_ReturnsOne() {
        // when: 11:05 ~ 11:15 (기존 11:00 ~ 11:10 과 5분 겹침)
        long count = attractionSlotRepository.countOverlappingSlots(
                rollerCoaster.getId(), DAY.withHour(11).withMinute(5), DAY.withHour(11).withMinute(15));

        // then
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("기존 슬롯이 끝나는 시각에 바로 시작하면 겹치지 않는다")
    void countOverlappingSlots_StartsAtExistingEnd_ReturnsZero() {
        // when: 11:10 ~ 11:20 (기존은 11:10 에 끝남)
        long count = attractionSlotRepository.countOverlappingSlots(
                rollerCoaster.getId(), DAY.withHour(11).withMinute(10), DAY.withHour(11).withMinute(20));

        // then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("기존 슬롯이 시작하는 시각에 딱 끝나면 겹치지 않는다")
    void countOverlappingSlots_EndsAtExistingStart_ReturnsZero() {
        // when: 10:50 ~ 11:00 (기존은 11:00 에 시작)
        long count = attractionSlotRepository.countOverlappingSlots(
                rollerCoaster.getId(), DAY.withHour(10).withMinute(50), DAY.withHour(11));

        // then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("취소된 슬롯과는 시간이 겹쳐도 세지 않는다")
    void countOverlappingSlots_CancelledSlot_ReturnsZero() {
        // when: 13:00 ~ 13:10 (취소된 슬롯과 완전히 같은 시간)
        long count = attractionSlotRepository.countOverlappingSlots(
                rollerCoaster.getId(), DAY.withHour(13), DAY.withHour(13).withMinute(10));

        // then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("다른 어트랙션의 슬롯과는 시간이 겹쳐도 세지 않는다")
    void countOverlappingSlots_OtherAttraction_ReturnsZero() {
        // when: 바이킹 기준으로 11:00 ~ 11:10 검사 (롤러코스터 슬롯과 같은 시간)
        long count = attractionSlotRepository.countOverlappingSlots(
                viking.getId(), DAY.withHour(11), DAY.withHour(11).withMinute(10));

        // then
        assertThat(count).isZero();
    }

    // ===== countOverlappingSlotsExcludingSlotId (시간 수정용) =====

    @Test
    @DisplayName("시간 수정 시 자기 자신과는 겹침 검사를 하지 않는다")
    void countOverlappingSlotsExcludingSlotId_OnlySelfOverlaps_ReturnsZero() {
        // when: morningSlot 을 11:05 ~ 11:15 로 옮기는 상황
        long count = attractionSlotRepository.countOverlappingSlotsExcludingSlotId(
                rollerCoaster.getId(), morningSlot.getId(),
                DAY.withHour(11).withMinute(5), DAY.withHour(11).withMinute(15));

        // then
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("시간 수정 시 자기 자신이 아닌 다른 슬롯과 겹치면 센다")
    void countOverlappingSlotsExcludingSlotId_OverlapsOtherSlot_ReturnsOne() {
        // given: 롤러코스터 14:00 ~ 14:10 슬롯 추가
        attractionSlotRepository.saveAndFlush(AttractionSlot.create(rollerCoaster, DAY.withHour(14), 10));

        // when: morningSlot 을 14:05 ~ 14:15 로 옮기려는 상황
        long count = attractionSlotRepository.countOverlappingSlotsExcludingSlotId(
                rollerCoaster.getId(), morningSlot.getId(),
                DAY.withHour(14).withMinute(5), DAY.withHour(14).withMinute(15));

        // then
        assertThat(count).isEqualTo(1);
    }

    // ===== findSlotsByAttractionIdAndDate =====

    @Test
    @DisplayName("취소 제외 옵션이면 취소 슬롯과 다른 날 슬롯이 빠진다")
    void findSlotsByAttractionIdAndDate_ExcludeCancelled_ReturnsActiveSlotsOfDay() {
        // when
        List<AttractionSlot> slots = attractionSlotRepository.findSlotsByAttractionIdAndDate(
                rollerCoaster.getId(), DAY, DAY.plusDays(1), false);

        // then
        assertThat(slots)
                .extracting(AttractionSlot::getStartsAt)
                .containsExactly(DAY.withHour(11));
    }

    @Test
    @DisplayName("취소 포함 옵션이면 취소 슬롯도 시작 시각이 빠른 순서로 나온다")
    void findSlotsByAttractionIdAndDate_IncludeCancelled_ReturnsAllSlotsOfDayInOrder() {
        // when
        List<AttractionSlot> slots = attractionSlotRepository.findSlotsByAttractionIdAndDate(
                rollerCoaster.getId(), DAY, DAY.plusDays(1), true);

        // then
        assertThat(slots)
                .extracting(AttractionSlot::getStartsAt)
                .containsExactly(DAY.withHour(11), DAY.withHour(13));
    }

    @Test
    @DisplayName("슬롯이 없는 어트랙션이면 빈 목록을 반환한다")
    void findSlotsByAttractionIdAndDate_NoSlots_ReturnsEmpty() {
        // when
        List<AttractionSlot> slots = attractionSlotRepository.findSlotsByAttractionIdAndDate(
                viking.getId(), DAY, DAY.plusDays(1), true);

        // then
        assertThat(slots).isEmpty();
    }

    // ===== findReservableSlots =====

    @Test
    @DisplayName("예약 가능한 슬롯만 골라 잔여 정원을 계산해서 돌려준다")
    void findReservableSlots_MixedSlots_ReturnsReservableWithRemainingCount() {
        // given
        AttractionSlot available = AttractionSlot.create(rollerCoaster, DAY.withHour(14), 10);
        ReflectionTestUtils.setField(available, "reservedCount", 3); // 잔여 7

        AttractionSlot full = AttractionSlot.create(rollerCoaster, DAY.withHour(15), 5);
        ReflectionTestUtils.setField(full, "reservedCount", 5);      // 잔여 0

        attractionSlotRepository.saveAllAndFlush(List.of(available, full));

        // when
        List<ReservableSlotView> result = attractionSlotRepository.findReservableSlots(
                List.of(rollerCoaster.getId()), RESERVABLE_FROM, TODAY_END);

        // then
        // 11시(마감 지남), 13시(취소), 15시(정원 마감), 다음 날 → 모두 제외
        assertThat(result).hasSize(1);
        ReservableSlotView slot = result.get(0);
        assertThat(slot.slotId()).isEqualTo(available.getId());
        assertThat(slot.attractionId()).isEqualTo(rollerCoaster.getId());
        assertThat(slot.startsAt()).isEqualTo(DAY.withHour(14));
        assertThat(slot.remainingCount()).isEqualTo(7); // 10 - 3
    }

    @Test
    @DisplayName("여러 어트랙션을 한 번에 조회하면 어트랙션 id 순, 그 안에서 시작 시각 순으로 정렬된다")
    void findReservableSlots_MultipleAttractions_SortedByAttractionThenStartsAt() {
        // given: 일부러 시간 순서를 섞어서 저장
        attractionSlotRepository.saveAllAndFlush(List.of(
                AttractionSlot.create(viking, DAY.withHour(16), 10),
                AttractionSlot.create(rollerCoaster, DAY.withHour(17), 10),
                AttractionSlot.create(viking, DAY.withHour(14), 10),
                AttractionSlot.create(rollerCoaster, DAY.withHour(15), 10)
        ));

        // when
        List<ReservableSlotView> result = attractionSlotRepository.findReservableSlots(
                List.of(rollerCoaster.getId(), viking.getId()), RESERVABLE_FROM, TODAY_END);

        // then: rollerCoaster 를 먼저 저장했으므로 id가 더 작다
        assertThat(result)
                .extracting(ReservableSlotView::attractionId, ReservableSlotView::startsAt)
                .containsExactly(
                        tuple(rollerCoaster.getId(), DAY.withHour(15)),
                        tuple(rollerCoaster.getId(), DAY.withHour(17)),
                        tuple(viking.getId(), DAY.withHour(14)),
                        tuple(viking.getId(), DAY.withHour(16))
                );
    }

    @Test
    @DisplayName("요청한 어트랙션 목록에 없는 어트랙션의 슬롯은 나오지 않는다")
    void findReservableSlots_NotRequestedAttraction_Excluded() {
        // given
        attractionSlotRepository.saveAndFlush(AttractionSlot.create(viking, DAY.withHour(14), 10));

        // when: 롤러코스터만 요청
        List<ReservableSlotView> result = attractionSlotRepository.findReservableSlots(
                List.of(rollerCoaster.getId()), RESERVABLE_FROM, TODAY_END);

        // then
        assertThat(result).isEmpty(); // 롤러코스터는 예약 가능한 슬롯 없음, 바이킹은 요청 안 함
    }
}
