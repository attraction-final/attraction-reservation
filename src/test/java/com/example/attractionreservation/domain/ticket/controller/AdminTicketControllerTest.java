package com.example.attractionreservation.domain.ticket.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryRequest;
import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryResponse;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminTicketControllerTest {

    private static final Long ADMIN_ID = 99L;

    @Mock
    private TicketService ticketService;

    private MockMvc mockMvc;
    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // Long principal 주입을 확인한다. standaloneSetup이므로 Security 권한 검증은 포함하지 않는다.
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                ADMIN_ID, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
        SecurityContextHolder.setContext(context);

        mockMvc = MockMvcBuilders.standaloneSetup(new AdminTicketController(ticketService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        logger = (Logger) LoggerFactory.getLogger(AdminTicketController.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("티켓 입장 성공 응답을 반환하고 티켓 ID와 관리자 ID만 INFO 로그로 기록한다")
    void createTicketEntry_Success_ReturnsResponseAndLogsAdminId() throws Exception {
        // given
        CreateTicketEntryRequest request = new CreateTicketEntryRequest("TKT-entry-test");
        CreateTicketEntryResponse response = new CreateTicketEntryResponse(
                10L, request.ticketCode(), TicketType.ALL_DAY, LocalDate.of(2026, 10, 31),
                TicketStatus.IN_USE, Instant.parse("2026-10-31T01:00:00Z")
        );
        when(ticketService.createTicketEntry(request)).thenReturn(response);

        // when
        var result = mockMvc.perform(post("/api/admin/tickets/entry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ticketCode":"TKT-entry-test"}
                        """));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("티켓 입장 처리에 성공했습니다."))
                .andExpect(jsonPath("$.data.ticketId").value(10))
                .andExpect(jsonPath("$.data.ticketCode").value(request.ticketCode()))
                .andExpect(jsonPath("$.data.type").value("ALL_DAY"))
                .andExpect(jsonPath("$.data.validDate").exists())
                .andExpect(jsonPath("$.data.status").value("IN_USE"))
                .andExpect(jsonPath("$.data.enteredAt").exists());
        verify(ticketService).createTicketEntry(request);
        verifyNoMoreInteractions(ticketService);
        assertThat(appender.list).singleElement().satisfies(logEvent -> {
            assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);
            assertThat(logEvent.getFormattedMessage())
                    .isEqualTo("event=TICKET_ENTERED ticketId=10 userId=99")
                    .doesNotContain(request.ticketCode());
            assertThat(logEvent.getThrowableProxy()).isNull();
        });
    }
}
