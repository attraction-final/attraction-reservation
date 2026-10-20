package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.dto.*;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attraction.repository.ReservableSlotView;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Mock
    private UpcomingReservationReader upcomingReservationReader; // 위로 옮김 (다른 @Mock과 같이)

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

    // 이용 예정 예약 건수 Mock 설정 (상태 변경 테스트에서 반복되는 부분)
    private void givenUpcomingReservationCount(Long attractionId, long count) {
        given(upcomingReservationReader.countUpcomingReservationsByAttractionId(eq(attractionId), any(LocalDateTime.class)))
                .willReturn(count);
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

    // 수정
    @Test
    @DisplayName("보낸 값만 수정되고, 보내지 않은(null) 값은 그대로 유지된다")
    void updateAttraction_PartialRequest_UpdatesOnlyGivenFields() {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.OPERATING);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
        given(attractionRepository.existsByNameAndIdNotAndDeletedAtIsNull("슈퍼 롤러코스터", 1L)).willReturn(false);

        // 이름과 소요 시간만 보냄 (설명, 위치는 null = "안 바꿀래요")
        UpdateAttractionRequest request = new UpdateAttractionRequest("슈퍼 롤러코스터", null, null, 12);

        // when
        SearchAttractionResponse response = attractionService.updateAttraction(1L, request);

        // then
        assertThat(response.name()).isEqualTo("슈퍼 롤러코스터");      // 바뀜
        assertThat(response.durationMinutes()).isEqualTo(12);          // 바뀜
        assertThat(response.description()).isEqualTo("빠른 속도의 코스터"); // 그대로
        assertThat(response.location()).isEqualTo("A구역");            // 그대로
        verify(attractionRepository).flush();                          // DB 반영을 호출했는지
    }

    @Test
    @DisplayName("다른 어트랙션이 쓰는 이름으로 수정하면 ATTRACTION_NAME_DUPLICATED 예외가 발생하고 값은 바뀌지 않는다")
    void updateAttraction_DuplicatedName_ThrowsException() {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.OPERATING);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
        given(attractionRepository.existsByNameAndIdNotAndDeletedAtIsNull("회전목마", 1L)).willReturn(true);

        UpdateAttractionRequest request = new UpdateAttractionRequest("회전목마", null, null, null);

        // when & then
        assertThatThrownBy(() -> attractionService.updateAttraction(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_NAME_DUPLICATED);

        assertThat(attraction.getName()).isEqualTo("롤러코스터");  // 이름이 바뀌지 않았는지
        verify(attractionRepository, never()).flush();
    }

    @Test
    @DisplayName("현재와 같은 이름을 보내면 중복 검사를 하지 않고 수정된다")
    void updateAttraction_SameName_SkipsDuplicateCheck() {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.OPERATING);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));

        // 이름은 지금과 똑같이, 설명만 바꿈
        UpdateAttractionRequest request = new UpdateAttractionRequest("롤러코스터", "더 빨라진 코스터", null, null);

        // when
        SearchAttractionResponse response = attractionService.updateAttraction(1L, request);

        // then
        verify(attractionRepository, never()).existsByNameAndIdNotAndDeletedAtIsNull(any(), any());
        assertThat(response.name()).isEqualTo("롤러코스터");
        assertThat(response.description()).isEqualTo("더 빨라진 코스터");
    }

    @Test
    @DisplayName("존재하지 않는 어트랙션을 수정하면 ATTRACTION_NOT_FOUND 예외가 발생한다")
    void updateAttraction_NotExists_ThrowsException() {
        // given
        given(attractionRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());
        UpdateAttractionRequest request = new UpdateAttractionRequest("슈퍼 롤러코스터", null, null, null);

        // when & then
        assertThatThrownBy(() -> attractionService.updateAttraction(99L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_NOT_FOUND);

        verify(attractionRepository, never()).existsByNameAndIdNotAndDeletedAtIsNull(any(), any());
        verify(attractionRepository, never()).flush();
    }

    // 상태 변경 - 성공
    @Test
    @DisplayName("이용 예정 예약이 없으면 운영중에서 점검으로 바꿀 수 있다")
    void updateAttractionStatus_NoUpcomingReservations_Success() {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.OPERATING);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
        givenUpcomingReservationCount(1L, 0L);
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("MAINTENANCE");

        // when
        UpdateAttractionStatusResponse response = attractionService.updateAttractionStatus(1L, request);

        // then
        assertThat(attraction.getOperationStatus()).isEqualTo(OperationStatus.MAINTENANCE); // 엔티티가 바뀌었는지
        assertThat(response.attractionId()).isEqualTo(1L);
        assertThat(response.previousStatus()).isEqualTo(OperationStatus.OPERATING);        // 바뀌기 전
        assertThat(response.operationStatus()).isEqualTo(OperationStatus.MAINTENANCE);     // 바뀐 후
        verify(attractionRepository).flush();                                              // DB 반영을 호출했는지
    }

    @Test
    @DisplayName("운영중으로 되돌릴 때는 이용 예정 예약을 확인하지 않는다")
    void updateAttractionStatus_ToOperating_DoesNotCheckReservations() {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.MAINTENANCE);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("OPERATING");

        // when
        UpdateAttractionStatusResponse response = attractionService.updateAttractionStatus(1L, request);

        // then
        assertThat(attraction.getOperationStatus()).isEqualTo(OperationStatus.OPERATING);
        assertThat(response.previousStatus()).isEqualTo(OperationStatus.MAINTENANCE);
        verify(upcomingReservationReader, never())
                .countUpcomingReservationsByAttractionId(anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("점검에서 일시중단처럼 비운영끼리 바꿀 때도 이용 예정 예약을 확인한다")
    void updateAttractionStatus_NonOperatingToNonOperating_ChecksReservations() {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.MAINTENANCE);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
        givenUpcomingReservationCount(1L, 0L);
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("SUSPENDED");

        // when
        attractionService.updateAttractionStatus(1L, request);

        // then
        assertThat(attraction.getOperationStatus()).isEqualTo(OperationStatus.SUSPENDED);
        verify(upcomingReservationReader)
                .countUpcomingReservationsByAttractionId(eq(1L), any(LocalDateTime.class));
    }

    // 상태 변경 - 실패 (서비스 검사 순서: 상태값 → 존재 여부 → 같은 상태 → 이용 예정 예약)
    @Test
    @DisplayName("허용되지 않은 상태값이면 어트랙션을 조회하기 전에 INVALID_ATTRACTION_STATUS 예외가 발생한다")
    void updateAttractionStatus_InvalidStatus_ThrowsException() {
        // given
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("BROKEN");

        // when & then
        assertThatThrownBy(() -> attractionService.updateAttractionStatus(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ATTRACTION_STATUS);
        verify(attractionRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
    }

    @Test
    @DisplayName("없거나 삭제된 어트랙션이면 ATTRACTION_NOT_FOUND 예외가 발생한다")
    void updateAttractionStatus_NotExists_ThrowsException() {
        // given
        given(attractionRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("MAINTENANCE");

        // when & then
        assertThatThrownBy(() -> attractionService.updateAttractionStatus(99L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_NOT_FOUND);
        verify(attractionRepository, never()).flush();
    }

    @Test
    @DisplayName("현재와 같은 상태로 변경하면 예약을 확인하기 전에 ATTRACTION_STATUS_SAME 예외가 발생한다")
    void updateAttractionStatus_SameStatus_ThrowsException() {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.OPERATING);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("OPERATING");

        // when & then
        assertThatThrownBy(() -> attractionService.updateAttractionStatus(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_STATUS_SAME);
        verify(upcomingReservationReader, never())
                .countUpcomingReservationsByAttractionId(anyLong(), any(LocalDateTime.class));
        verify(attractionRepository, never()).flush();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAINTENANCE", "SUSPENDED", "CLOSED"})
    @DisplayName("운영중이 아닌 상태로 바꿀 때 이용 예정 예약이 있으면 예외가 발생하고 상태는 그대로다")
    void updateAttractionStatus_HasUpcomingReservations_ThrowsException(String status) {
        // given
        Attraction attraction = createAttractionWithId(1L, OperationStatus.OPERATING);
        given(attractionRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(attraction));
        givenUpcomingReservationCount(1L, 3L);
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest(status);

        // when & then
        assertThatThrownBy(() -> attractionService.updateAttractionStatus(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이용 예정 예약이 3건 있어 상태를 변경할 수 없습니다. 예약을 먼저 처리해 주세요.")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTRACTION_HAS_UPCOMING_RESERVATIONS);

        assertThat(attraction.getOperationStatus()).isEqualTo(OperationStatus.OPERATING); // 상태가 바뀌지 않았는지
        verify(attractionRepository, never()).flush();
    }
}