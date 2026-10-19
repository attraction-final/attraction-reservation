package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.dto.CreateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionResponse;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AttractionService {

    private final AttractionRepository attractionRepository;

    // 어트랙션 등록
    @Transactional
    public CreateAttractionResponse createAttraction(CreateAttractionRequest request) {
        validateNameNotDuplicated(request.name()); // 등록하려는 어트랙션 이름이 이미 존재하는지 검사

        // 요청 DTO의 데이터를 이용해 Attraction 엔티티를 생성, Attraction.create()는 프로젝트에서 별도로 정의한 생성 메서드
        Attraction attraction = Attraction.create(
                request.name(),
                request.description(),
                request.location(),
                request.durationMinutes()
        );
        Attraction savedAttraction = attractionRepository.save(attraction); // 생성한 엔티티를 Repository를 통해 저장, 저장 결과를 savedAttraction 변수에 담음

        return CreateAttractionResponse.from(savedAttraction); // 저장된 엔티티를 응답 DTO로 변환하여 반환
    }

    private void validateNameNotDuplicated(String name) {
        if (attractionRepository.existsByNameAndDeletedAtIsNull(name)) {
            throw new BusinessException(ErrorCode.ATTRACTION_NAME_DUPLICATED);
        }
    }

}
