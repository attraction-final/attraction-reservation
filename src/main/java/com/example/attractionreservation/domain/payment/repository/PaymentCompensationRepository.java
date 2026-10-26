package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentCompensationRepository extends JpaRepository<PaymentCompensation, Long> {
    Optional<PaymentCompensation> findByPgId(String pgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentCompensation> findWithLockById(Long id);

    // 처리 대상 후보. 점유는 findWithLockById로 행을 잠근 뒤 상태를 다시 확인해 결정한다.
    // 조건은 PaymentCompensation.isProcessable과 같다(REQUESTED는 시각과 무관하게 대상).
    @Query("""
    SELECT c.id
    FROM PaymentCompensation c
    WHERE c.status = :requested
       OR (c.status = :failed AND c.nextRetryAt <= :now)
       OR (c.status = :processing AND c.updatedAt <= :staleBefore)
    ORDER BY c.id
    """)
    List<Long> findProcessableIds(CompensationStatus requested, CompensationStatus failed,
                                  CompensationStatus processing, Instant now, Instant staleBefore,
                                  Pageable pageable);
}
