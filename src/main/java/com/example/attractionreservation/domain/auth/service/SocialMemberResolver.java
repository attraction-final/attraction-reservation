package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.dto.SocialUserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

@Service
@RequiredArgsConstructor
public class SocialMemberResolver {

    private final SocialMemberService socialMemberService;

    public Long resolve(SocialUserInfo info) {
        try {
            return socialMemberService.findOrCreate(info);
        } catch (DataIntegrityViolationException exception) {
            if (!isDuplicateKey(exception)) {
                throw exception;
            }

            // 첫 호출의 트랜잭션이 롤백된 뒤 새 트랜잭션에서 한 번만 재확인한다.
            // 같은 소셜 계정이면 기존 회원을 반환하고,
            // 다른 계정의 이메일 중복이면 EMAIL_ALREADY_EXISTS로 거부한다.
            return socialMemberService.findOrCreate(info);
        }
    }

    private boolean isDuplicateKey(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                // MySQL의 중복 키 오류 코드다.
                if (sqlException.getErrorCode() == 1062) {
                    return true;
                }

                // H2 테스트 환경의 유일성 제약 위반 코드다.
                if ("23505".equals(sqlException.getSQLState())) {
                    return true;
                }
            }

            cause = cause.getCause();
        }

        return false;
    }
}