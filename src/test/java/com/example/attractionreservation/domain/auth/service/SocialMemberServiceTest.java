package com.example.attractionreservation.domain.auth.service;

import com.example.attractionreservation.domain.user.entity.UserStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.attractionreservation.domain.auth.dto.SocialUserInfo;
import com.example.attractionreservation.domain.user.entity.AuthProvider;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialMemberServiceTest {

    @Mock
    private UserRepository userRepository;

    private SocialMemberService socialMemberService;

    @BeforeEach
    void setUp() {
        socialMemberService = new SocialMemberService(userRepository);
    }

    @Test
    @DisplayName("기존 소셜 회원은 제공자와 회원 ID로 식별한다")
    void existingMember_returnsUserId() {
        User user = User.createSocial(
                "original@example.com",
                "기존회원",
                AuthProvider.GOOGLE,
                "google-123"
        );

        // DB를 사용하지 않는 테스트이므로 저장된 회원 ID를 직접 설정한다.
        ReflectionTestUtils.setField(user, "id", 1L);

        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, "google-123"
        )).thenReturn(Optional.of(user));

        var info = new SocialUserInfo(
                AuthProvider.GOOGLE,
                "google-123",
                "changed@example.com",
                true,
                "변경된이름"
        );

        assertThat(socialMemberService.findOrCreate(info)).isEqualTo(1L);

        // 제공자의 이메일이 바뀌어도 신규 가입이나 자동 계정 연결을 하지 않는다.
        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("신규 회원의 이메일이 없으면 가입을 제한한다")
    void newMember_withoutEmail_rejected() {
        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.KAKAO, "123"
        )).thenReturn(Optional.empty());

        var info = new SocialUserInfo(
                AuthProvider.KAKAO, "123", null, false, "회원"
        );

        assertThatThrownBy(() -> socialMemberService.findOrCreate(info))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode()
                        ).isEqualTo("EMAIL_REQUIRED")
                );

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동일 이메일의 기존 회원이 있으면 자동 연결하지 않는다")
    void newMember_duplicateEmail_rejected() {
        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, "google-123"
        )).thenReturn(Optional.empty());

        when(userRepository.existsByEmail("user@example.com"))
                .thenReturn(true);

        var info = new SocialUserInfo(
                AuthProvider.GOOGLE,
                "google-123",
                "user@example.com",
                true,
                "회원"
        );

        assertThatThrownBy(() -> socialMemberService.findOrCreate(info))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode()
                        ).isEqualTo("EMAIL_ALREADY_EXISTS")
                );

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("신규 소셜 회원은 비밀번호 없이 USER·ACTIVE로 생성한다")
    void newMember_createsSocialUser() {
        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, "google-new"
        )).thenReturn(Optional.empty());

        when(userRepository.existsByEmail("new@example.com"))
                .thenReturn(false);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> {
                    User savedUser = invocation.getArgument(0);
                    ReflectionTestUtils.setField(savedUser, "id", 2L);
                    return savedUser;
                });

        var info = new SocialUserInfo(
                AuthProvider.GOOGLE,
                "google-new",
                "new@example.com",
                true,
                null
        );

        assertThat(socialMemberService.findOrCreate(info)).isEqualTo(2L);

        // 실제 저장하려는 회원 정보를 확인한다.
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());

        User savedUser = captor.getValue();

        assertThat(savedUser.getEmail()).isEqualTo("new@example.com");
        assertThat(savedUser.getPassword()).isNull();
        assertThat(savedUser.getProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(savedUser.getProviderUserId()).isEqualTo("google-new");
        assertThat(savedUser.getRole().name()).isEqualTo("USER");
        assertThat(savedUser.getStatus().name()).isEqualTo("ACTIVE");

        // 제공자 닉네임이 없으면 임시 닉네임을 생성한다.
        assertThat(savedUser.getNickname())
                .startsWith("회원_")
                .hasSizeLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("신규 회원의 이메일이 검증되지 않았으면 가입을 제한한다")
    void newMember_unverifiedEmail_rejected() {
        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, "google-new"
        )).thenReturn(Optional.empty());

        var info = new SocialUserInfo(
                AuthProvider.GOOGLE,
                "google-new",
                "new@example.com",
                false,
                "회원"
        );

        assertThatThrownBy(() -> socialMemberService.findOrCreate(info))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode()
                        ).isEqualTo("EMAIL_NOT_VERIFIED")
                );

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = UserStatus.class,
            names = "ACTIVE",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("ACTIVE가 아닌 기존 소셜 회원은 로그인을 제한한다")
    void existingMember_notActive_rejected(UserStatus status) {
        User user = User.createSocial(
                "user@example.com",
                "회원",
                AuthProvider.GOOGLE,
                "google-123"
        );

        // 각 회원 상태를 재현하기 위해 테스트에서만 필드 값을 설정한다.
        ReflectionTestUtils.setField(user, "status", status);

        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, "google-123"
        )).thenReturn(Optional.of(user));

        var info = new SocialUserInfo(
                AuthProvider.GOOGLE,
                "google-123",
                "user@example.com",
                true,
                "회원"
        );

        assertThatThrownBy(() -> socialMemberService.findOrCreate(info))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode()
                        ).isEqualTo("USER_NOT_ACTIVE")
                );

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).saveAndFlush(any());
    }
}