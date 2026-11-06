package com.example.attractionreservation.domain.ticket.entity.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TicketStatusTest {

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    @DisplayName("모든 티켓 상태에서 null 상태로 전이할 수 없다")
    void canTransitTo_NullTarget_ReturnsFalse(TicketStatus currentStatus) {
        // given
        TicketStatus nextStatus = null;

        // when
        boolean allowed = currentStatus.canTransitTo(nextStatus);

        // then
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    @DisplayName("모든 티켓 상태에서 자기 상태로 전이할 수 없다")
    void canTransitTo_SameStatus_ReturnsFalse(TicketStatus currentStatus) {
        // given
        TicketStatus nextStatus = currentStatus;

        // when
        boolean allowed = currentStatus.canTransitTo(nextStatus);

        // then
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "UNUSED, IN_USE",
            "UNUSED, REFUNDED",
            "UNUSED, EXPIRED",
            "IN_USE, USED"
    })
    @DisplayName("상태 전이표에 정의된 다음 상태로는 전이할 수 있다")
    void canTransitTo_AllowedTransition_ReturnsTrue(TicketStatus currentStatus, TicketStatus nextStatus) {
        // given
        // 비즈니스 규칙의 상태 전이표에 정의된 조합이다.

        // when
        boolean allowed = currentStatus.canTransitTo(nextStatus);

        // then
        assertThat(allowed).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "UNUSED, USED",
            "IN_USE, UNUSED",
            "IN_USE, EXPIRED",
            "IN_USE, REFUNDED",
            "USED, UNUSED",
            "USED, IN_USE",
            "USED, EXPIRED",
            "USED, REFUNDED",
            "EXPIRED, UNUSED",
            "EXPIRED, IN_USE",
            "EXPIRED, USED",
            "EXPIRED, REFUNDED",
            "REFUNDED, UNUSED",
            "REFUNDED, IN_USE",
            "REFUNDED, USED",
            "REFUNDED, EXPIRED"
    })
    @DisplayName("상태 전이표에 없는 변경과 종료 상태에서 다른 상태로의 변경은 거부한다")
    void canTransitTo_DisallowedTransition_ReturnsFalse(TicketStatus currentStatus, TicketStatus nextStatus) {
        // given
        // 자기 상태를 제외한 나머지 미허용 조합을 검증한다.

        // when
        boolean allowed = currentStatus.canTransitTo(nextStatus);

        // then
        assertThat(allowed).isFalse();
    }
}
