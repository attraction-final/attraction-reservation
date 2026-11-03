package com.example.attractionreservation.domain.payment.scheduler;

import com.example.attractionreservation.domain.payment.facade.WebhookEventProcessor;
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
class WebhookEventSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WebhookEventProcessor.class, () -> mock(WebhookEventProcessor.class))
            .withUserConfiguration(WebhookEventScheduler.class);

    @Test
    @DisplayName("스케줄러가 실행되면 설정한 배치 크기만큼 웹훅 처리를 요청한다.")
    void processPendingWebhooks_scheduled_ProcessWithBatchSize() {
        //given
        WebhookEventProcessor processor = mock(WebhookEventProcessor.class);
        WebhookEventScheduler scheduler = new WebhookEventScheduler(processor, 30);

        //when
        scheduler.processPendingWebhooks();

        //then
        verify(processor).processPending(30);
    }

    @Test
    @DisplayName("처리 중 예외가 나도 스케줄러 밖으로 던지지 않는다.")
    void processPendingWebhooks_error_Swallowed() {
        //given
        WebhookEventProcessor processor = mock(WebhookEventProcessor.class);
        willThrow(new DataAccessResourceFailureException("db down")).given(processor).processPending(30);
        WebhookEventScheduler scheduler = new WebhookEventScheduler(processor, 30);

        //when & then
        assertThatCode(scheduler::processPendingWebhooks).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("설정이 없으면 스케줄러를 등록한다.")
    void scheduler_noProperty_Registered() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(WebhookEventScheduler.class));
    }

    @Test
    @DisplayName("enabled가 false면 스케줄러를 등록하지 않는다.")
    void scheduler_disabled_NotRegistered() {
        contextRunner
                .withPropertyValues("payment.webhook.scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WebhookEventScheduler.class));
    }

    @Test
    @DisplayName("처리한 웹훅이 있으면 처리 건수와 소요 시간을 INFO 로그로 남긴다.")
    void processPendingWebhooks_processed_LogCompleted(CapturedOutput output) {
        //given
        WebhookEventProcessor processor = mock(WebhookEventProcessor.class);
        given(processor.processPending(30)).willReturn(3);
        WebhookEventScheduler scheduler = new WebhookEventScheduler(processor, 30);

        //when
        scheduler.processPendingWebhooks();

        //then
        assertThat(output.getAll().lines().filter(line -> line.contains("event=WEBHOOK_BATCH_COMPLETED")))
                .singleElement().asString().contains("INFO").contains("count=3 durationMs=");
    }

    @Test
    @DisplayName("처리한 웹훅이 없으면 배치 완료 로그를 남기지 않는다.")
    void processPendingWebhooks_nothingProcessed_NotLogCompleted(CapturedOutput output) {
        //given
        WebhookEventProcessor processor = mock(WebhookEventProcessor.class);
        given(processor.processPending(30)).willReturn(0);
        WebhookEventScheduler scheduler = new WebhookEventScheduler(processor, 30);

        //when
        scheduler.processPendingWebhooks();

        //then
        assertThat(output).doesNotContain("event=WEBHOOK_BATCH_COMPLETED");
    }
}
