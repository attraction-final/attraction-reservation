package com.example.attractionreservation.domain.user.facade;

import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.service.UserWithdrawalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalFacade {

    private static final int TOKEN_DELETE_MAX_ATTEMPTS = 3;

    private final UserWithdrawalService userWithdrawalService;
    private final RefreshTokenRepository refreshTokenRepository;

    public void withdraw(Long userId) {
        // DB 탈퇴 처리에 실패하면 예외를 그대로 전달한다.
        userWithdrawalService.withdraw(userId);

        // DB 탈퇴가 완료된 뒤 토큰 삭제를 시도한다.
        deleteRefreshToken(userId);
    }

    private void deleteRefreshToken(Long userId) {
        DataAccessException lastException = null;

        for (int attempt = 1;
             attempt <= TOKEN_DELETE_MAX_ATTEMPTS;
             attempt++) {
            try {
                refreshTokenRepository.delete(userId);
                return;
            } catch (DataAccessException exception) {
                lastException = exception;
            }
        }

        // 모든 시도가 실패한 경우에만 반복문 밖에서 한 번 기록한다.
        // 이미 완료된 DB 탈퇴의 성공 응답은 유지한다.
        log.error(
                "event=USER_REFRESH_TOKEN_DELETE_FAILED "
                        + "userId={} target=redis retry={} errorCode={}",
                userId,
                TOKEN_DELETE_MAX_ATTEMPTS - 1,
                ErrorCode.INTERNAL_SERVER_ERROR.name(),
                lastException
        );
    }
}
