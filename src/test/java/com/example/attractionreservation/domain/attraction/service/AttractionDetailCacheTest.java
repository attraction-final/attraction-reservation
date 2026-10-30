package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusRequest;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 캐시는 진짜 Redis가 있어야 동작하므로 Testcontainers(MySQL + Redis) 사용
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class AttractionDetailCacheTest {

    @Autowired private AttractionService attractionService;
    @Autowired private AttractionRepository attractionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long attractionId;
    private String originalName;

    @BeforeEach
    void setUp() {
        // 테스트마다 새 어트랙션 → 새 id → 캐시 키도 새것 (테스트끼리 캐시가 섞이지 않음)
        originalName = "캐시테스트-" + System.nanoTime();
        attractionId = attractionRepository.save(
                Attraction.create(originalName, "설명", "A구역", 10, 5)).getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM attractions WHERE id = ?", attractionId);
    }

    @Test
    @DisplayName("두 번째 상세 조회는 캐시에서 꺼내므로, DB를 몰래 바꿔도 예전 값이 나온다")
    void secondCall_ComesFromCache() {
        attractionService.findAttractionById(attractionId);                 // 1) DB에서 읽고 캐시에 저장

        jdbcTemplate.update("UPDATE attractions SET name = ? WHERE id = ?",  // 2) 서비스를 거치지 않고
                "몰래바꾼이름", attractionId);                                //    DB만 직접 변경

        SearchAttractionResponse second = attractionService.findAttractionById(attractionId); // 3)
        assertThat(second.name()).isEqualTo(originalName);                 // 예전 이름 = DB에 안 갔다는 증거
    }

    @Test
    @DisplayName("관리자가 정보를 수정하면 캐시가 지워져 새 이름이 보인다")
    void update_EvictsCache() {
        attractionService.findAttractionById(attractionId);   // 캐시 채우기

        String newName = "새이름-" + System.nanoTime();
        attractionService.updateAttraction(attractionId,
                new UpdateAttractionRequest(newName, null, null, null, null));

        assertThat(attractionService.findAttractionById(attractionId).name())
                .isEqualTo(newName);   // 기다리지 않고 바로 확인
    }

    @Test
    @DisplayName("운영 상태를 바꾸면 캐시가 지워져 새 상태가 보인다")
    void changeStatus_EvictsCache() {
        attractionService.findAttractionById(attractionId);

        attractionService.updateAttractionStatus(attractionId,
                new UpdateAttractionStatusRequest("MAINTENANCE"));

        assertThat(attractionService.findAttractionById(attractionId).operationStatus())
                .isEqualTo(OperationStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("삭제하면 캐시가 지워져 상세 조회가 ATTRACTION_NOT_FOUND 가 된다")
    void delete_EvictsCache() {
        attractionService.findAttractionById(attractionId);

        attractionService.deleteAttraction(attractionId);

        assertThatThrownBy(() -> attractionService.findAttractionById(attractionId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ATTRACTION_NOT_FOUND));
    }
}
