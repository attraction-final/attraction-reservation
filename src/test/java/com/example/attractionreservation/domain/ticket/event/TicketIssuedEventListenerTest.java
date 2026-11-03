package com.example.attractionreservation.domain.ticket.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 전체 애플리케이션 대신 Listener와 H2 transaction만 연결해 AFTER_COMMIT 동작을 검증한다.
@SpringJUnitConfig(TicketIssuedEventListenerTest.TestConfig.class)
class TicketIssuedEventListenerTest {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        logger = (Logger) LoggerFactory.getLogger(TicketIssuedEventListener.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);

        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("발급 event의 로그는 transaction 진행 중에는 없고 commit 성공 후 INFO로 한 번 기록된다")
    void handleTicketIssued_TransactionCommits_LogsOnceAfterCommit() {
        // given
        TicketIssuedEvent event = new TicketIssuedEvent(20L, 10L, 3);

        // when
        transactionTemplate.executeWithoutResult(status -> {
            applicationEventPublisher.publishEvent(event);
            assertThat(appender.list).isEmpty();
        });

        // then
        assertThat(appender.list).singleElement().satisfies(logEvent -> {
            assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);
            assertThat(logEvent.getFormattedMessage())
                    .isEqualTo("event=TICKETS_ISSUED orderId=20 userId=10 count=3");
            assertThat(logEvent.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("발급 event를 발행했더라도 이후 예외로 transaction이 rollback되면 성공 로그가 기록되지 않는다")
    void handleTicketIssued_TransactionRollsBack_DoesNotLog() {
        // given
        TicketIssuedEvent event = new TicketIssuedEvent(20L, 10L, 3);
        IllegalStateException failure = new IllegalStateException("event 발행 후 처리 실패");

        // when & then
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            applicationEventPublisher.publishEvent(event);
            assertThat(appender.list).isEmpty();
            throw failure;
        })).isSameAs(failure);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("transaction 없이 발행한 event는 기본 설정에 따라 성공 로그를 기록하지 않는다")
    void handleTicketIssued_NoTransaction_DoesNotLog() {
        // given
        TicketIssuedEvent event = new TicketIssuedEvent(20L, 10L, 3);

        // when
        applicationEventPublisher.publishEvent(event);

        // then
        assertThat(appender.list).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(TicketIssuedEventListener.class)
    static class TestConfig {

        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .generateUniqueName(true)
                    .setType(EmbeddedDatabaseType.H2)
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(EmbeddedDatabase dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
