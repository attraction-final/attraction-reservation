package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.dto.SocialUserInfo;
import com.example.attractionreservation.domain.auth.oauth2.SocialLoginError;
import com.example.attractionreservation.domain.user.entity.AuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialMemberResolverTest {

    @Mock
    private SocialMemberService socialMemberService;

    private SocialMemberResolver resolver;

    private final SocialUserInfo info = new SocialUserInfo(
            AuthProvider.GOOGLE,
            "google-123",
            "user@example.com",
            true,
            "회원"
    );

    @BeforeEach
    void setUp() {
        resolver = new SocialMemberResolver(socialMemberService);
    }

    @Test
    @DisplayName("정상 조회는 재시도 없이 회원 ID를 반환한다")
    void resolve_success_returnsUserId() {
        when(socialMemberService.findOrCreate(info)).thenReturn(1L);

        assertThat(resolver.resolve(info)).isEqualTo(1L);

        verify(socialMemberService, times(1)).findOrCreate(info);
    }

    @Test
    @DisplayName("중복 키 오류 후 재확인한 회원 ID를 반환한다")
    void resolve_duplicateKey_rechecksOnce() {
        when(socialMemberService.findOrCreate(info))
                .thenThrow(duplicateKey())
                .thenReturn(1L);

        assertThat(resolver.resolve(info)).isEqualTo(1L);

        verify(socialMemberService, times(2)).findOrCreate(info);
    }

    @Test
    @DisplayName("재확인에서 발생한 이메일 중복 오류를 그대로 전달한다")
    void resolve_duplicateEmail_propagatesBusinessError() {
        var emailError =
                SocialLoginError.EMAIL_ALREADY_EXISTS.toException();

        when(socialMemberService.findOrCreate(info))
                .thenThrow(duplicateKey())
                .thenThrow(emailError);

        assertThatThrownBy(() -> resolver.resolve(info))
                .isSameAs(emailError);

        verify(socialMemberService, times(2)).findOrCreate(info);
    }

    @Test
    @DisplayName("중복 키 이외의 DB 오류는 재시도하지 않는다")
    void resolve_otherIntegrityError_doesNotRetry() {
        var error = new DataIntegrityViolationException(
                "test-not-null-violation",
                new SQLException("test", "23000", 1048)
        );

        when(socialMemberService.findOrCreate(info)).thenThrow(error);

        assertThatThrownBy(() -> resolver.resolve(info)).isSameAs(error);

        verify(socialMemberService, times(1)).findOrCreate(info);
    }

    @Test
    @DisplayName("두 번째 호출도 중복 키 오류이면 더 이상 재시도하지 않는다")
    void resolve_repeatedConflict_stopsAfterSecondCall() {
        var firstError = duplicateKey();
        var secondError = duplicateKey();

        when(socialMemberService.findOrCreate(info))
                .thenThrow(firstError)
                .thenThrow(secondError);

        assertThatThrownBy(() -> resolver.resolve(info))
                .isSameAs(secondError);

        verify(socialMemberService, times(2)).findOrCreate(info);
    }

    private DataIntegrityViolationException duplicateKey() {
        // MySQL 중복 키 오류를 재현한다.
        return new DataIntegrityViolationException(
                "test-duplicate-key",
                new SQLException("test", "23000", 1062)
        );
    }
}