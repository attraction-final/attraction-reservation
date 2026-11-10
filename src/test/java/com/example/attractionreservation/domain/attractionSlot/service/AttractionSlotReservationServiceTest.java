package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttractionSlotReservationServiceTest {

    private static final Long SLOT_ID = 20L;

    @Mock
    private AttractionSlotRepository attractionSlotRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private AttractionSlotService attractionSlotService;

    @ParameterizedTest
    @CsvSource({
            "8, 9, OPEN, false",
            "9, 10, CLOSED, true"
    })
    @DisplayName("잠금을 보유한 슬롯에서 인원을 증가시키고 마지막 자리를 자동 마감한다")
    void tryReservationCapacity_success(
            int previousCount,
            int updatedCount,
            SlotStatus expectedStatus,
            boolean expectedAutoClosed
    ) {
        // given
        Attraction attraction = mock(Attraction.class);
        when(attraction.getDurationMinutes()).thenReturn(10);

        AttractionSlot slot = spy(AttractionSlot.create(
                attraction,
                LocalDateTime.of(2026, 10, 25, 16, 0),
                10
        ));

        ReflectionTestUtils.setField(slot, "id", SLOT_ID);
        ReflectionTestUtils.setField(
                slot,
                "reservedCount",
                previousCount
        );

        when(attractionSlotRepository.findById(SLOT_ID))
                .thenReturn(Optional.of(slot));
        when(entityManager.getLockMode(slot)).thenReturn(LockModeType.PESSIMISTIC_WRITE);

        // when
        attractionSlotService.tryReservationCapacity(SLOT_ID);

        // then
        assertThat(slot.getReservedCount()).isEqualTo(updatedCount);
        assertThat(slot.getRemainingCount()).isEqualTo(10 - updatedCount);
        assertThat(slot.getStatus()).isEqualTo(expectedStatus);
        assertThat(slot.isAutoClosed()).isEqualTo(expectedAutoClosed);

        InOrder inOrder = inOrder(
                attractionSlotRepository,
                entityManager,
                slot
        );

        inOrder.verify(attractionSlotRepository).findById(SLOT_ID);
        inOrder.verify(entityManager).getLockMode(slot);
        inOrder.verify(slot).acquireReservationCapacity();
        verify(attractionSlotRepository, never()).tryReserveCapacity(any(), any());
        verify(entityManager, never()).refresh(any());
    }

    @Test
    @DisplayName("잠금을 보유해도 정원이 가득 차면 인원을 변경하지 않는다")
    void tryReservationCapacity_failed() {
        // given
        Attraction attraction = mock(Attraction.class);
        when(attraction.getDurationMinutes()).thenReturn(10);
        AttractionSlot slot = AttractionSlot.create(
                attraction, LocalDateTime.of(2026, 10, 25, 16, 0), 10);
        ReflectionTestUtils.setField(slot, "reservedCount", 10);
        when(attractionSlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        when(entityManager.getLockMode(slot)).thenReturn(LockModeType.PESSIMISTIC_WRITE);

        // when & then
        assertThatThrownBy(() ->
                attractionSlotService.tryReservationCapacity(SLOT_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED
                        )
        );

        assertThat(slot.getReservedCount()).isEqualTo(10);
        assertThat(slot.getStatus()).isEqualTo(SlotStatus.OPEN);
        assertThat(slot.isAutoClosed()).isFalse();
        verify(attractionSlotRepository, never()).tryReserveCapacity(any(), any());
        verify(entityManager, never()).refresh(any());
    }

    @ParameterizedTest
    @EnumSource(value = LockModeType.class, names = "PESSIMISTIC_WRITE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("비관적 쓰기 잠금을 보유하지 않으면 인원 변경 전에 호출을 거부한다")
    void rejectCapacityAcquisitionWithoutWriteLock(LockModeType lockMode) {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(attractionSlotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        when(entityManager.getLockMode(slot)).thenReturn(lockMode);

        // when & then
        assertThatThrownBy(() -> attractionSlotService.tryReservationCapacity(SLOT_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(slot, never()).acquireReservationCapacity();
        verify(attractionSlotRepository, never()).tryReserveCapacity(any(), any());
        verify(entityManager, never()).refresh(any());
    }

    @Test
    @DisplayName("예약에 사용할 슬롯을 ID로 조회한다")
    void findAttractionSlotById_success() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);

        when(attractionSlotRepository.findById(SLOT_ID))
                .thenReturn(Optional.of(slot));

        // when
        AttractionSlot result =
                attractionSlotService.findAttractionSlotById(SLOT_ID);

        // then
        assertThat(result).isSameAs(slot);
        verify(attractionSlotRepository).findById(SLOT_ID);
    }

    @Test
    @DisplayName("슬롯이 없으면 슬롯 조회 실패 예외를 반환한다")
    void findAttractionSlotById_notFound() {
        // given
        when(attractionSlotRepository.findById(SLOT_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                attractionSlotService.findAttractionSlotById(SLOT_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.SLOT_NOT_FOUND)
        );

        verify(attractionSlotRepository).findById(SLOT_ID);
    }
}
