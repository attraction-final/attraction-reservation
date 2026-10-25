package com.example.attractionreservation.domain.refund.scheduler;

import com.example.attractionreservation.domain.refund.facade.RefundProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RefundSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RefundProcessor.class, () -> mock(RefundProcessor.class))
            .withUserConfiguration(RefundScheduler.class);

    @Test
    @DisplayName("스케줄러가 실행되면 설정한 처리 배치 크기와 확인 배치 크기로 환불 처리를 요청한다.")
    void processPendingRefunds_scheduled_ProcessWithBatchSize() {
        //given
        RefundProcessor processor = mock(RefundProcessor.class);
        RefundScheduler scheduler = new RefundScheduler(processor, 20, 5);

        //when
        scheduler.processPendingRefunds();

        //then
        verify(processor).processPending(20, 5);
    }

    @Test
    @DisplayName("처리 중 예외가 나도 스케줄러 밖으로 던지지 않는다.")
    void processPendingRefunds_error_Swallowed() {
        //given
        RefundProcessor processor = mock(RefundProcessor.class);
        willThrow(new DataAccessResourceFailureException("db down")).given(processor).processPending(20, 5);
        RefundScheduler scheduler = new RefundScheduler(processor, 20, 5);

        //when & then
        assertThatCode(scheduler::processPendingRefunds).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정이 없으면 스케줄러를 등록한다.")
    void scheduler_noProperty_Registered() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(RefundScheduler.class));
    }

    @Test
    @DisplayName("enabled가 false면 스케줄러를 등록하지 않는다.")
    void scheduler_disabled_NotRegistered() {
        contextRunner
                .withPropertyValues("payment.refund.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RefundScheduler.class));
    }
}
