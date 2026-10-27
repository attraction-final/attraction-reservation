package com.example.attractionreservation.domain.user.facade;

import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.service.UserWithdrawalService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalFacadeTest {

    @Mock
    private UserWithdrawalService userWithdrawalService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private UserWithdrawalFacade facade;

    @BeforeEach
    void setUp() {
        facade = new UserWithdrawalFacade(
                userWithdrawalService,
                refreshTokenRepository
        );
    }

    @Test
    @DisplayName("DB 탈퇴 처리 후 Refresh Token을 삭제한다")
    void withdraw_success_deletesTokenAfterServiceReturns() {
        facade.withdraw(1L);

        var order = inOrder(
                userWithdrawalService,
                refreshTokenRepository
        );

        order.verify(userWithdrawalService).withdraw(1L);
        order.verify(refreshTokenRepository).delete(1L);
    }

    @Test
    @DisplayName("DB 탈퇴 처리에 실패하면 Refresh Token을 삭제하지 않는다")
    void withdraw_serviceFailed_keepsToken() {
        var error = new BusinessException(
                ErrorCode.USER_WITHDRAWAL_UNUSED_TICKET
        );

        doThrow(error).when(userWithdrawalService).withdraw(1L);

        assertThatThrownBy(() -> facade.withdraw(1L))
                .isSameAs(error);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("토큰 삭제가 일시적으로 실패하면 재시도한다")
    void withdraw_tokenDeletionTemporarilyFailed_retriesSuccessfully() {
        doThrow(new RedisConnectionFailureException("테스트 연결 장애"))
                .doNothing()
                .when(refreshTokenRepository).delete(1L);

        assertThatCode(() -> facade.withdraw(1L))
                .doesNotThrowAnyException();

        verify(userWithdrawalService, times(1)).withdraw(1L);
        verify(refreshTokenRepository, times(2)).delete(1L);
    }

    @Test
    @DisplayName("토큰 삭제가 계속 실패해도 완료된 탈퇴는 성공으로 반환한다")
    void withdraw_tokenDeletionAlwaysFailed_returnsSuccessfully() {
        doThrow(new RedisConnectionFailureException("테스트 연결 장애"))
                .when(refreshTokenRepository).delete(1L);

        assertThatCode(() -> facade.withdraw(1L))
                .doesNotThrowAnyException();

        // 탈퇴 처리를 반복하지 않고 토큰 삭제만 세 번 시도한다.
        verify(userWithdrawalService, times(1)).withdraw(1L);
        verify(refreshTokenRepository, times(3)).delete(1L);
    }
}