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

    // PG 취소 결과를 조회로 확인할 차례인 요청. 조건은 PaymentCompensation.isPgCheckable과 같다.
    // 한 번에 확인하는 건수가 작으므로 확인 시각이 오래 지난 것부터 고른다. 결과 기록이 실패해 확인 시각이 그대로인 건이
    // 계속 앞에 뽑혀도 id 순과 달리 다른 건이 밀리지 않는다.
    @Query("""
    SELECT c.id
    FROM PaymentCompensation c
    WHERE c.status = :pgPending AND c.nextRetryAt <= :now
    ORDER BY c.nextRetryAt, c.id
    """)
    List<Long> findPgCheckableIds(CompensationStatus pgPending, Instant now, Pageable pageable);
}
