package com.example.attractionreservation.domain.user.facade;

import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.service.UserWithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserWithdrawalFacade {

    private final UserWithdrawalService userWithdrawalService;
    private final RefreshTokenRepository refreshTokenRepository;

    public void withdraw(Long userId) {
        // DB 트랜잭션이 정상 커밋된 뒤 Refresh Token을 삭제한다.
        userWithdrawalService.withdraw(userId);
        refreshTokenRepository.delete(userId);
    }
}