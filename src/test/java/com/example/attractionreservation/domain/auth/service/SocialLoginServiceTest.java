package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.auth.dto.SocialUserInfo;
import com.example.attractionreservation.domain.auth.oauth2.SocialLoginError;
import com.example.attractionreservation.domain.auth.repository.SocialLoginCodeRepository;
import com.example.attractionreservation.domain.user.entity.AuthProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialLoginServiceTest {

    @Mock
    private SocialMemberResolver socialMemberResolver;

    @Mock
    private SocialLoginCodeRepository loginCodeRepository;

    private SocialLoginService socialLoginService;

    private final SocialUserInfo info = new SocialUserInfo(
            AuthProvider.GOOGLE,
            "google-123",
            "user@example.com",
            true,
            "회원"
    );

    @BeforeEach
    void setUp() {
        socialLoginService = new SocialLoginService(
                socialMemberResolver,
                loginCodeRepository
        );
    }

    @Test
    @DisplayName("회원 확인 후 해당 회원의 로그인 코드를 발급한다")
    void login_resolvesMemberBeforeIssuingCode() {
        when(socialMemberResolver.resolve(info)).thenReturn(1L);
        when(loginCodeRepository.issue(1L)).thenReturn("test-code");

        assertThat(socialLoginService.login(info)).isEqualTo("test-code");

        var order = inOrder(socialMemberResolver, loginCodeRepository);
        order.verify(socialMemberResolver).resolve(info);
        order.verify(loginCodeRepository).issue(1L);
    }

    @Test
    @DisplayName("회원 확인에 실패하면 로그인 코드를 발급하지 않는다")
    void login_memberRejected_doesNotIssueCode() {
        var error = SocialLoginError.USER_NOT_ACTIVE.toException();
        when(socialMemberResolver.resolve(info)).thenThrow(error);

        assertThatThrownBy(() -> socialLoginService.login(info))
                .isSameAs(error);

        verifyNoInteractions(loginCodeRepository);
    }

    @Test
    @DisplayName("코드 저장 오류를 호출자에게 전달한다")
    void login_storageFailed_propagatesError() {
        var error = new IllegalStateException("test-storage-failure");
        when(socialMemberResolver.resolve(info)).thenReturn(1L);
        when(loginCodeRepository.issue(1L)).thenThrow(error);

        assertThatThrownBy(() -> socialLoginService.login(info))
                .isSameAs(error);
    }
}