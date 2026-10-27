package com.example.attractionreservation.domain.payment.scheduler;

import com.example.attractionreservation.domain.payment.facade.PaymentCompensationProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentCompensationSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PaymentCompensationProcessor.class, () -> mock(PaymentCompensationProcessor.class))
            .withUserConfiguration(PaymentCompensationScheduler.class);

    @Test
    @DisplayName("스케줄러가 실행되면 설정한 배치 크기만큼 보상 처리를 요청한다.")
    void processPendingCompensations_scheduled_ProcessWithBatchSize() {
        //given
        PaymentCompensationProcessor processor = mock(PaymentCompensationProcessor.class);
        PaymentCompensationScheduler scheduler = new PaymentCompensationScheduler(processor, 20);

        //when
        scheduler.processPendingCompensations();

        //then
        verify(processor).processPending(20);
    }

    @Test
    @DisplayName("처리 중 예외가 나도 스케줄러 밖으로 던지지 않는다.")
    void processPendingCompensations_error_Swallowed() {
        //given
        PaymentCompensationProcessor processor = mock(PaymentCompensationProcessor.class);
        willThrow(new DataAccessResourceFailureException("db down")).given(processor).processPending(20);
        PaymentCompensationScheduler scheduler = new PaymentCompensationScheduler(processor, 20);

        //when & then
        assertThatCode(scheduler::processPendingCompensations).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정이 없으면 스케줄러를 등록한다.")
    void scheduler_noProperty_Registered() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(PaymentCompensationScheduler.class));
    }

    @Test
    @DisplayName("enabled가 false면 스케줄러를 등록하지 않는다.")
    void scheduler_disabled_NotRegistered() {
        contextRunner
                .withPropertyValues("payment.compensation.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PaymentCompensationScheduler.class));
    }
}
