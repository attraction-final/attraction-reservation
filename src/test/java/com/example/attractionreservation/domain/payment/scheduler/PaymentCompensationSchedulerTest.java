package com.example.attractionreservation.domain.payment.scheduler;

import com.example.attractionreservation.domain.payment.facade.PaymentCompensationProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class PaymentCompensationSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PaymentCompensationProcessor.class, () -> mock(PaymentCompensationProcessor.class))
            .withUserConfiguration(PaymentCompensationScheduler.class);

    @Test
    @DisplayName("스케줄러가 실행되면 설정한 배치 크기만큼 보상 처리를 요청한다.")
    void processPendingCompensations_scheduled_ProcessWithBatchSize() {
        //given
        PaymentCompensationProcessor processor = mock(PaymentCompensationProcessor.class);
        PaymentCompensationScheduler scheduler = new PaymentCompensationScheduler(processor, 20, 5);

        //when
        scheduler.processPendingCompensations();

        //then
        verify(processor).processPending(20, 5);
    }

    @Test
    @DisplayName("처리 중 예외가 나도 스케줄러 밖으로 던지지 않는다.")
    void processPendingCompensations_error_Swallowed() {
        //given
        PaymentCompensationProcessor processor = mock(PaymentCompensationProcessor.class);
        willThrow(new DataAccessResourceFailureException("db down")).given(processor).processPending(20, 5);
        PaymentCompensationScheduler scheduler = new PaymentCompensationScheduler(processor, 20, 5);

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

    @Test
    @DisplayName("처리한 보상 요청이 있으면 처리 건수와 소요 시간을 INFO 로그로 남긴다.")
    void processPendingCompensations_processed_LogCompleted(CapturedOutput output) {
        //given
        PaymentCompensationProcessor processor = mock(PaymentCompensationProcessor.class);
        given(processor.processPending(20, 5)).willReturn(3);
        PaymentCompensationScheduler scheduler = new PaymentCompensationScheduler(processor, 20, 5);

        //when
        scheduler.processPendingCompensations();

        //then
        assertThat(output.getAll().lines().filter(line -> line.contains("event=PAYMENT_COMPENSATION_BATCH_COMPLETED")))
                .singleElement().asString().contains("INFO").contains("count=3 durationMs=");
    }

    @Test
    @DisplayName("처리한 보상 요청이 없으면 배치 완료 로그를 남기지 않는다.")
    void processPendingCompensations_nothingProcessed_NotLogCompleted(CapturedOutput output) {
        //given
        PaymentCompensationProcessor processor = mock(PaymentCompensationProcessor.class);
        given(processor.processPending(20, 5)).willReturn(0);
        PaymentCompensationScheduler scheduler = new PaymentCompensationScheduler(processor, 20, 5);

        //when
        scheduler.processPendingCompensations();

        //then
        assertThat(output).doesNotContain("event=PAYMENT_COMPENSATION_BATCH_COMPLETED");
    }
}
