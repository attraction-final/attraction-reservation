package com.example.attractionreservation.domain.user.entity;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserStatusTest {

    @ParameterizedTest
    @CsvSource({
            "ACTIVE, INACTIVE",
            "ACTIVE, SUSPENDED",
            "INACTIVE, ACTIVE",
            "SUSPENDED, ACTIVE"
    })
    @DisplayName("허용된 회원 상태 전이는 성공한다")
    void updateStatus_allowedTransition_changesStatus(
            UserStatus currentStatus,
            UserStatus newStatus
    ) {
        // given
        User user = createUser(currentStatus);

        // when
        user.updateStatus(newStatus);

        // then
        assertThat(user.getStatus()).isEqualTo(newStatus);
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    @DisplayName("현재와 동일한 상태 요청은 거부하고 기존 상태를 유지한다")
    void updateStatus_sameStatus_throwsConflict(UserStatus status) {
        // given
        User user = createUser(status);

        // when & then
        assertThatThrownBy(() -> user.updateStatus(status))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_STATUS_SAME)
                );

        assertThat(user.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @CsvSource({
            "ACTIVE, WITHDRAWN",
            "INACTIVE, SUSPENDED",
            "INACTIVE, WITHDRAWN",
            "SUSPENDED, INACTIVE",
            "SUSPENDED, WITHDRAWN",
            "WITHDRAWN, ACTIVE",
            "WITHDRAWN, INACTIVE",
            "WITHDRAWN, SUSPENDED"
    })
    @DisplayName("허용되지 않은 상태 전이는 거부하고 기존 상태를 유지한다")
    void updateStatus_disallowedTransition_throwsConflict(
            UserStatus currentStatus,
            UserStatus newStatus
    ) {
        // given
        User user = createUser(currentStatus);

        // when & then
        assertThatThrownBy(() -> user.updateStatus(newStatus))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_INVALID_STATUS_TRANSITION)
                );

        assertThat(user.getStatus()).isEqualTo(currentStatus);
    }

    private User createUser(UserStatus status) {
        User user = User.builder()
                .email("status@example.com")
                .password("encoded-password")
                .nickname("상태테스트")
                .build();

        // 운영용 변경 메서드를 우회하는 것은 초기 상태를 구성하는 테스트에서만 허용한다.
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }
}