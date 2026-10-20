package com.example.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.dto.CreateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionResponse;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attraction.service.AttractionService;
import com.example.attractionreservation.domain.attraction.service.UpcomingReservationReader;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttractionServiceTest {

    @Mock
    private AttractionRepository attractionRepository;

    @Mock
    private UpcomingReservationReader upcomingReservationReader;

    @InjectMocks
    private AttractionService attractionService;

    private Attraction createRollerCoaster() {
        return Attraction.create("롤러코스터", "빠른 속도의 코스터", "A구역", 10);
    }

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
}