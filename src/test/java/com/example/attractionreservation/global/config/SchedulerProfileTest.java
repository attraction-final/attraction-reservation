package com.example.attractionreservation.global.config;

import com.example.attractionreservation.domain.attractionSlot.scheduler.AttractionSlotGenerationScheduler;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotGenerationService;
import com.example.attractionreservation.domain.order.scheduler.OrderCancelScheduler;
import com.example.attractionreservation.domain.order.service.OrderService;
import com.example.attractionreservation.domain.payment.facade.PaymentCompensationProcessor;
import com.example.attractionreservation.domain.payment.facade.WebhookEventProcessor;
import com.example.attractionreservation.domain.payment.scheduler.PaymentCompensationScheduler;
import com.example.attractionreservation.domain.payment.scheduler.WebhookEventScheduler;
import com.example.attractionreservation.domain.refund.facade.RefundProcessor;
import com.example.attractionreservation.domain.refund.scheduler.RefundScheduler;
import com.example.attractionreservation.domain.ticket.scheduler.TicketExpirationScheduler;
import com.example.attractionreservation.domain.ticket.service.TicketExpirationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * api 프로필(application-api.yml)로 띄운 API 인스턴스에서는 스케줄러가 돌지 않고,
 * 프로필 없이 띄운 worker에서는 스케줄러가 모두 등록되는지 확인한다.
 */
class SchedulerProfileTest {

    private static final List<Class<?>> SCHEDULERS = List.of(
            OrderCancelScheduler.class,
            WebhookEventScheduler.class,
            PaymentCompensationScheduler.class,
            RefundScheduler.class,
            TicketExpirationScheduler.class,
            AttractionSlotGenerationScheduler.class
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(OrderService.class, () -> mock(OrderService.class))
            .withBean(WebhookEventProcessor.class, () -> mock(WebhookEventProcessor.class))
            .withBean(PaymentCompensationProcessor.class, () -> mock(PaymentCompensationProcessor.class))
            .withBean(RefundProcessor.class, () -> mock(RefundProcessor.class))
            .withBean(TicketExpirationService.class, () -> mock(TicketExpirationService.class))
            .withBean(AttractionSlotGenerationService.class, () -> mock(AttractionSlotGenerationService.class))
            .withPropertyValues("ticket.expiration.scheduler.batch-size=100")
            .withUserConfiguration(SCHEDULERS.toArray(Class<?>[]::new));

    @Test
    @DisplayName("api 프로필 설정이면 스케줄러를 하나도 등록하지 않는다.")
    void schedulers_ApiProfile_NotRegistered() {
        // given
        PropertySource<?> apiProfile = loadYaml("application-api.yml");

        // when & then
        contextRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(apiProfile))
                .run(context -> SCHEDULERS.forEach(scheduler ->
                        assertThat(context).doesNotHaveBean(scheduler)));
    }

    @Test
    @DisplayName("api 프로필 없이 띄우면 스케줄러를 모두 등록한다.")
    void schedulers_WithoutApiProfile_AllRegistered() {
        // given
        // when & then
        contextRunner.run(context -> SCHEDULERS.forEach(scheduler ->
                assertThat(context).hasSingleBean(scheduler)));
    }

    private static PropertySource<?> loadYaml(String path) {
        try {
            return new YamlPropertySourceLoader().load(path, new ClassPathResource(path)).get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
