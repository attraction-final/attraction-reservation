package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.WebhookEvent;
import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {
    Optional<WebhookEvent> findByWebhookId(String webhookId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WebhookEvent> findWithLockById(Long id);

    // 처리 대상 후보. 점유는 findWithLockById로 행을 잠근 뒤 상태를 다시 확인해 결정한다.
    @Query("""
    SELECT w.id
    FROM WebhookEvent w
    WHERE w.status = :received
       OR (w.status = :failed AND w.nextRetryAt <= :now)
       OR (w.status = :processing AND w.updatedAt <= :staleBefore)
    ORDER BY w.id
    """)
    List<Long> findProcessableIds(
            @Param("received") WebhookStatus received,
            @Param("failed") WebhookStatus failed,
            @Param("processing") WebhookStatus processing,
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable
    );
}
