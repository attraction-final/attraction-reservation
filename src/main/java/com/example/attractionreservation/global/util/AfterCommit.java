package com.example.attractionreservation.global.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 트랜잭션이 커밋된 뒤에 실행할 작업. 롤백되면 남지 않아야 하는 비즈니스 이벤트 로그 등에 사용한다.
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * 트랜잭션 안이면 커밋된 뒤에 실행하고, 롤백되면 실행하지 않는다.
     * 트랜잭션 밖(스케줄러, 단위 테스트 등)이면 바로 실행한다.
     *
     * <p>커밋 뒤에는 지연 로딩을 하지 않도록, 작업에 쓸 값은 호출 전에 꺼내 둔다.
     * 작업이 예외를 던져도 호출한 쪽으로 전파하지 않는다.
     */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(action);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runSafely(action);
            }
        });
    }

    // 커밋 뒤의 예외는 커밋한 호출자에게 전파돼, 이미 커밋된 처리를 실패로 알리게 된다(재시도 시 중복 처리).
    // 확정된 결과를 덮어쓰지 않도록 예외를 여기서 남기고 삼킨다.
    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("event=AFTER_COMMIT_ACTION_FAILED", e);
        }
    }
}
