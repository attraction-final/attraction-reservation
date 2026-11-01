package com.example.attractionreservation.global.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AfterCommitTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션 밖이면 작업을 바로 실행한다.")
    void run_withoutTransaction_RunImmediately() {
        //given
        AtomicInteger count = new AtomicInteger();

        //when
        AfterCommit.run(count::incrementAndGet);

        //then
        assertThat(count).hasValue(1);
    }

    @Test
    @DisplayName("트랜잭션 안이면 커밋 전에는 실행하지 않고 커밋된 뒤에 실행한다.")
    void run_inTransaction_RunAfterCommit() {
        //given
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger count = new AtomicInteger();

        //when
        AfterCommit.run(count::incrementAndGet);
        int countBeforeCommit = count.get();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        //then
        assertThat(countBeforeCommit).isZero();
        assertThat(count).hasValue(1);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 작업을 실행하지 않는다.")
    void run_inTransactionRolledBack_NotRun() {
        //given
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger count = new AtomicInteger();

        //when
        AfterCommit.run(count::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        //then
        assertThat(count).hasValue(0);
    }
}
