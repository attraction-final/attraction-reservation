package com.example.attractionreservation.global.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 트랜잭션이 커밋된 뒤에 실행할 작업. 롤백되면 남지 않아야 하는 비즈니스 이벤트 로그 등에 사용한다.
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * 트랜잭션 안이면 커밋된 뒤에 실행하고, 롤백되면 실행하지 않는다.
     * 트랜잭션 밖(스케줄러, 단위 테스트 등)이면 바로 실행한다.
     *
     * <p>커밋 뒤에는 지연 로딩을 하지 않도록, 작업에 쓸 값은 호출 전에 꺼내 둔다.
     */
    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
