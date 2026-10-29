package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotCapacityRequest;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 관리자 작업과 손님 예약(또는 다른 관리자)이 "동시에" 일어나는 상황을 재현한다.
// 락은 진짜 DB에서만 의미가 있으므로 Testcontainers MySQL을 사용한다.
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class AttractionSlotLockConcurrencyTest {

    private static final LocalDate TOMORROW = KstTime.now().toLocalDate().plusDays(1);

    @Autowired private AttractionSlotService attractionSlotService;
    @Autowired private AttractionRepository attractionRepository;
    @Autowired private AttractionSlotRepository attractionSlotRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Long attractionId;

    @BeforeEach
    void setUp() {
        // 이름 중복을 피하려고 매번 다른 이름을 쓴다
        attractionId = attractionRepository.save(
                Attraction.create("락테스트-" + System.nanoTime(), "설명", "A구역", 10, 5)).getId();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        // 내가 만든 데이터만 지운다 (다른 테스트 데이터는 건드리지 않음)
        jdbcTemplate.update("DELETE FROM attraction_slots WHERE attraction_id = ?", attractionId);
        jdbcTemplate.update("DELETE FROM attractions WHERE id = ?", attractionId);
    }

    // ===== 락 ①: 관리자 수정·삭제 vs 손님 예약 =====

    @Test
    @DisplayName("정원 수정 중에 손님이 예약해도 예약 인원이 사라지지 않는다")
    void updateCapacity_WhileCustomerReserves_KeepsReservedCount() throws Exception {
        Long slotId = createSlot(5, 3); // 정원 5, 예약 3

        Future<?> customer = runSecondBeforeFirstCommits(
                () -> attractionSlotService.updateAttractionSlotCapacity(
                        slotId, new UpdateAttractionSlotCapacityRequest(6)),   // 관리자: 정원 5 → 6
                () -> attractionSlotService.tryReservationCapacity(slotId));  // 손님: 예약 +1

        customer.get(5, TimeUnit.SECONDS); // 손님 예약은 성공해야 한다

        AttractionSlot slot = attractionSlotRepository.findById(slotId).orElseThrow();
        assertThat(slot.getCapacity()).isEqualTo(6);
        assertThat(slot.getReservedCount()).isEqualTo(4); // 3 + 손님 1명
    }

    @Test
    @DisplayName("삭제 중에 들어온 예약은 실패하고, 취소된 슬롯에 예약이 생기지 않는다")
    void delete_WhileCustomerReserves_RejectsReservation() {
        Long slotId = createSlot(5, 0); // 예약 0명이라 삭제 가능한 슬롯

        Future<?> customer = runSecondBeforeFirstCommits(
                () -> attractionSlotService.deleteAttractionSlot(slotId),
                () -> attractionSlotService.tryReservationCapacity(slotId));

        assertFailsWith(customer, ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED);

        AttractionSlot slot = attractionSlotRepository.findById(slotId).orElseThrow();
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.CANCELLED);
        assertThat(slot.getReservedCount()).isZero();
    }

    // ===== 락 ②: 관리자 둘이 겹치는 슬롯을 동시에 등록 =====

    @Test
    @DisplayName("두 관리자가 겹치는 슬롯을 동시에 등록하면 하나만 저장된다")
    void create_OverlappingAtSameTime_SavesOnlyOne() {
        Future<?> secondAdmin = runSecondBeforeFirstCommits(
                () -> attractionSlotService.createAttractionSlot(attractionId,
                        new CreateAttractionSlotRequest(TOMORROW.atTime(10, 0), 20)),  // 10:00~10:10
                () -> attractionSlotService.createAttractionSlot(attractionId,
                        new CreateAttractionSlotRequest(TOMORROW.atTime(10, 5), 20))); // 10:05~10:15 (겹침)

        assertFailsWith(secondAdmin, ErrorCode.SLOT_TIME_OVERLAP);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attraction_slots WHERE attraction_id = ?", Integer.class, attractionId);
        assertThat(count).isEqualTo(1);
    }

    // ===== 도우미 =====

    // 정원 capacity, 예약 reserved명인 "내일 10:00" 슬롯을 만든다
    private Long createSlot(int capacity, int reserved) {
        Long slotId = transactionTemplate.execute(status -> {
            Attraction attraction = attractionRepository.findById(attractionId).orElseThrow();
            return attractionSlotRepository.save(
                    AttractionSlot.create(attraction, TOMORROW.atTime(10, 0), capacity)).getId();
        });
        for (int i = 0; i < reserved; i++) {
            attractionSlotService.tryReservationCapacity(slotId); // 실제 예약과 같은 방법으로 +1
        }
        return slotId;
    }

    // ★ 핵심 도우미
    // 1) first 를 트랜잭션 안에서 실행한다 (아직 커밋 전)
    // 2) 커밋 "직전"에 second 를 다른 스레드에서 실행한다
    // 3) 0.5초 기다린 뒤 first 를 커밋한다
    // → 락이 없으면 second 가 먼저 끝나고, 락이 있으면 second 는 first 커밋까지 기다린다.
    private Future<?> runSecondBeforeFirstCommits(Runnable first, Runnable second) {
        AtomicReference<Future<?>> secondResult = new AtomicReference<>();
        transactionTemplate.executeWithoutResult(status -> {
            first.run();
            secondResult.set(executor.submit(second));
            sleep(500);
        });
        return secondResult.get();
    }

    // 다른 스레드에서 BusinessException(errorCode)이 터졌는지 확인
    private static void assertFailsWith(Future<?> future, ErrorCode errorCode) {
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
