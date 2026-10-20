package com.example.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.dto.CreateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionCondition;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionListResponse;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attraction.repository.ReservableSlotView;
import com.example.attractionreservation.domain.attraction.service.AttractionService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttractionServiceTest {

    @Mock
    private AttractionRepository attractionRepository;

    @InjectMocks
    private AttractionService attractionService;

    // 테스트 데이터 helper

    private Attraction createRollerCoaster() {
        return Attraction.create("롤러코스터", "빠른 속도의 코스터", "A구역", 10);
    }

    // DB에 저장하지 않은 엔티티는 id가 null이라서 직접 넣어 줌
    private Attraction createAttractionWithId(Long id, OperationStatus status) {
        Attraction attraction = createRollerCoaster();
        ReflectionTestUtils.setField(attraction, "id", id);
        ReflectionTestUtils.setField(attraction, "operationStatus", status);
        return attraction;
    }

    private SearchAttractionCondition createCondition() {
        // keyword, operating, reservable, sort, page, size 순서라고 가정
        return new SearchAttractionCondition(0, 10, null, null, null, null);
    }

    // 검색 결과(어트랙션 목록) Mock 설정
    private void givenSearchResult(List<Attraction> attractions) {
        given(attractionRepository.searchAttractions(
                any(),          // keyword (String)
                anyBoolean(),   // operating (boolean)
                anyBoolean(),   // reservable (boolean)
                any(),          // sort (String)
                any(),          // reservableFrom (LocalDateTime)
                any(),          // todayEnd (LocalDateTime)
                any()           // pageable
        )).willReturn(new PageImpl<>(attractions));
    }

    // ReservableSlotView는 인터페이스라서 테스트용 구현체를 만듦
    private record TestSlotView(Long slotId, Long attractionId, LocalDateTime startsAt, Integer remainingCount)
            implements ReservableSlotView {
        @Override public Long getSlotId() { return slotId; }
        @Override public Long getAttractionId() { return attractionId; }
        @Override public LocalDateTime getStartsAt() { return startsAt; }
        @Override public Integer getRemainingCount() { return remainingCount; }
    }

    // 등록

    @Test
    @DisplayName("이름이 중복되지 않으면 운영중 상태로 등록된다")
    void createAttraction_UniqueName_Success() {
        // given
        CreateAttractionRequest request = new CreateAttractionRequest("롤러코스터", "빠른 속도의 코스터", "A구역", 10);
        given(attractionRepository.existsByNameAndDeletedAtIsNull("롤러코스터")).willReturn(false);
        given(attractionRepository.save(any(Attraction.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        CreateAttractionResponse response = attractionService.createAttraction(request);

        // then
        assertThat(response.name()).isEqualTo("롤러코스터");
        assertThat(response.operationStatus()).isEqualTo(OperationStatus.OPERATING);
    }

    @Test
    @DisplayName("이미 있는 이름으로 등록하면 ATTRACTION_NAME_DUPLICATED 예외가 발생한다")
    void createAttraction_DuplicatedName_ThrowsException() {
        // given
        CreateAttractionRequest request = new CreateAttractionRequest("롤러코스터", "빠른 속도의 코스터", "A구역", 10);
        given(attractionRepository.existsByNameAndDeletedAtIsNull("롤러코스터")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> attractionService.createAttraction(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_NAME_DUPLICATED);
        verify(attractionRepository, never()).save(any());
    }

    // 목록 조회

    @Test
    @DisplayName("운영중인 어트랙션이 없으면 슬롯을 조회하지 않고 nextSlot은 null이다")
    void findAttractionAll_NoOperatingAttraction_SkipsSlotQuery() {
        // given
        Attraction maintenance = createAttractionWithId(1L, OperationStatus.MAINTENANCE);
        givenSearchResult(List.of(maintenance));

        // when
        SearchAttractionListResponse response = attractionService.findAttractionAll(createCondition());

        // then
        verify(attractionRepository, never()).findReservableSlots(anyList(), any(), any());
        assertThat(response.content().get(0).nextSlot()).isNull();
    }

    @Test
    @DisplayName("같은 어트랙션에 슬롯이 여러 개면 시작 시각이 가장 빠른 슬롯이 nextSlot이 된다")
    void findAttractionAll_MultipleSlots_SelectsEarliest() {
        // given
        Attraction operating = createAttractionWithId(1L, OperationStatus.OPERATING);
        givenSearchResult(List.of(operating));

        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        // 일부러 순서를 섞어서 넣음 (14시 → 11시 → 16시)
        List<ReservableSlotView> slots = List.of(
                new TestSlotView(10L, 1L, today.withHour(14), 5),
                new TestSlotView(11L, 1L, today.withHour(11), 3),
                new TestSlotView(12L, 1L, today.withHour(16), 8)
        );
        given(attractionRepository.findReservableSlots(eq(List.of(1L)), any(), any())).willReturn(slots);

        // when
        SearchAttractionListResponse response = attractionService.findAttractionAll(createCondition());

        // then
        assertThat(response.content().get(0).nextSlot().slotId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("운영중이 아닌 어트랙션의 nextSlot은 null이고, 운영중인 어트랙션만 슬롯을 조회한다")
    void findAttractionAll_NotOperatingAttraction_NextSlotIsNull() {
        // given
        Attraction operating = createAttractionWithId(1L, OperationStatus.OPERATING);
        Attraction suspended = createAttractionWithId(2L, OperationStatus.SUSPENDED);
        givenSearchResult(List.of(operating, suspended));

        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        given(attractionRepository.findReservableSlots(eq(List.of(1L)), any(), any()))
                .willReturn(List.of(new TestSlotView(10L, 1L, today.withHour(14), 5)));

        // when
        SearchAttractionListResponse response = attractionService.findAttractionAll(createCondition());

        // then
        assertThat(response.content().get(0).nextSlot()).isNotNull();
        assertThat(response.content().get(1).nextSlot()).isNull();
    }

    // 상세 조회

    @Test
    @DisplayName("존재하지 않는 어트랙션을 조회하면 ATTRACTION_NOT_FOUND 예외가 발생한다")
    void findAttractionById_NotExists_ThrowsException() {
        // given
        given(attractionRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> attractionService.findAttractionById(99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_NOT_FOUND);
    }
}