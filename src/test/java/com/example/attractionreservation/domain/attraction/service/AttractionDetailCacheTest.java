package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 캐시는 진짜 Redis가 있어야 동작하므로 Testcontainers(MySQL + Redis) 사용
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class AttractionDetailCacheTest {

    private static final Long ADMIN_ID = 1L; // 로그용 관리자 ID

    @Autowired private AttractionService attractionService;
    @Autowired private AttractionRepository attractionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // ★ 추가: Redis 키를 직접 확인하고, 트랜잭션을 직접 열기 위한 도구들
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private AttractionDetailCacheEvictor attractionDetailCacheEvictor;
    @Autowired private PlatformTransactionManager transactionManager;

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
        stringRedisTemplate.delete(cacheKey()); // ★ 추가: Redis 에 남은 키도 정리
    }

    // ★ 추가: RedisCacheManager 가 실제로 쓰는 키 모양 ("캐시이름::id")
    private String cacheKey() {
        return "attractionDetail::" + attractionId;
    }

    private boolean isCached() {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey()));
    }

    private void prepareDetailCache() {
        attractionService.findAttractionById(attractionId);

        await()
                .alias("상세 조회 결과의 Redis 저장 완료")
                .atMost(Duration.ofSeconds(5))
                .until(this::isCached);
    }

    // ===== 기존 테스트 (그대로) =====

    @Test
    @DisplayName("두 번째 상세 조회는 캐시에서 꺼내므로, DB를 몰래 바꿔도 예전 값이 나온다")
    void secondCall_ComesFromCache() {
        prepareDetailCache();                // 1) DB에서 읽고 캐시에 저장

        jdbcTemplate.update("UPDATE attractions SET name = ? WHERE id = ?",  // 2) 서비스를 거치지 않고
                "몰래바꾼이름", attractionId);                                //    DB만 직접 변경

        SearchAttractionResponse second = attractionService.findAttractionById(attractionId); // 3)
        assertThat(second.name()).isEqualTo(originalName);                 // 예전 이름 = DB에 안 갔다는 증거
    }

    @Test
    @DisplayName("관리자가 정보를 수정하면 캐시가 지워져 새 이름이 보인다")
    void update_EvictsCache() {
        prepareDetailCache();   // 캐시 채우기

        String newName = "새이름-" + System.nanoTime();
        attractionService.updateAttraction(ADMIN_ID, attractionId,
                new UpdateAttractionRequest(newName, null, null, null, null));

        assertThat(attractionService.findAttractionById(attractionId).name())
                .isEqualTo(newName);   // 기다리지 않고 바로 확인
    }

    @Test
    @DisplayName("운영 상태를 바꾸면 캐시가 지워져 새 상태가 보인다")
    void changeStatus_EvictsCache() {
        prepareDetailCache();

        attractionService.updateAttractionStatus(ADMIN_ID, attractionId,
                new UpdateAttractionStatusRequest("MAINTENANCE"));

        assertThat(attractionService.findAttractionById(attractionId).operationStatus())
                .isEqualTo(OperationStatus.MAINTENANCE);
    }

    @Test
    @DisplayName("삭제하면 캐시가 지워져 상세 조회가 ATTRACTION_NOT_FOUND 가 된다")
    void delete_EvictsCache() {
        prepareDetailCache();

        attractionService.deleteAttraction(ADMIN_ID, attractionId);

        assertThatThrownBy(() -> attractionService.findAttractionById(attractionId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ATTRACTION_NOT_FOUND));
    }

    // ===== ★ 추가 테스트 =====

    @Test
    @DisplayName("상세 조회 결과는 Evictor 가 지우는 키와 같은 이름(attractionDetail::{id})으로 저장된다")
    void cacheKey_MatchesEvictorKey() {
        prepareDetailCache();

        // ↓ 디버깅용: Redis 에 실제로 어떤 키가 들어 있는지 출력 (원인 확인 후 삭제)
        System.out.println("[DEBUG] id=" + attractionId
                + ", 찾는 키=" + cacheKey()
                + ", Redis 에 있는 키=" + stringRedisTemplate.keys("attractionDetail*"));

        assertThat(isCached()).isTrue();
    }

    @Test
    @DisplayName("트랜잭션 안에서는 커밋 전까지 캐시가 남아 있고, 커밋한 뒤에 지워진다")
    void evict_HappensOnlyAfterCommit() {
        prepareDetailCache();

        assertThat(isCached())
                .as("상세 조회 직후 캐시가 저장되어 있어야 한다")
                .isTrue();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            assertThat(isCached())
                    .as("트랜잭션 진입 직후에도 캐시가 있어야 한다")
                    .isTrue();

            attractionDetailCacheEvictor.evictAfterCommit(attractionId);

            assertThat(isCached())
                    .as("삭제를 요청해도 커밋 전에는 캐시가 남아 있어야 한다")
                    .isTrue();
        });

        assertThat(isCached())
                .as("커밋 후에는 캐시가 삭제되어야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 캐시는 지워지지 않는다")
    void evict_SkippedOnRollback() {
        prepareDetailCache();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            attractionDetailCacheEvictor.evictAfterCommit(attractionId);
            status.setRollbackOnly();          // 강제로 롤백
        });

        assertThat(isCached()).isTrue();
    }
}