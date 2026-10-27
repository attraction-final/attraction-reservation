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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.stream.Stream;
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

    @ParameterizedTest
    @MethodSource("invalidIdentityCases")
    @DisplayName("잘못된 소셜 식별 정보는 DB 조회 전에 거부한다")
    void invalidIdentity_rejectedBeforeRepositoryAccess(
            SocialUserInfo info,
            String expectedError
    ) {
        assertThatThrownBy(() -> socialMemberService.findOrCreate(info))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode()
                        ).isEqualTo(expectedError)
                );

        verifyNoInteractions(userRepository);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments>
    invalidIdentityCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        null,
                        "INVALID_USER_INFO"
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        new SocialUserInfo(
                                AuthProvider.LOCAL,
                                "123",
                                "user@example.com",
                                true,
                                "회원"
                        ),
                        "UNSUPPORTED_PROVIDER"
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        new SocialUserInfo(
                                AuthProvider.GOOGLE,
                                "   ",
                                "user@example.com",
                                true,
                                "회원"
                        ),
                        "INVALID_PROVIDER_USER_ID"
                ),
                org.junit.jupiter.params.provider.Arguments.of(
                        new SocialUserInfo(
                                AuthProvider.GOOGLE,
                                "a".repeat(256),
                                "user@example.com",
                                true,
                                "회원"
                        ),
                        "INVALID_PROVIDER_USER_ID"
                )
        );
    }

    @Test
    @DisplayName("이메일 50자와 제공자 회원 ID 255자는 저장할 수 있다")
    void maximumLengths_allowed() {
        String email = "a".repeat(38) + "@example.com";
        String providerUserId = "a".repeat(255);

        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, providerUserId
        )).thenReturn(Optional.empty());

        when(userRepository.existsByEmail(email)).thenReturn(false);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    ReflectionTestUtils.setField(user, "id", 3L);
                    return user;
                });

        var info = new SocialUserInfo(
                AuthProvider.GOOGLE,
                providerUserId,
                email,
                true,
                "회원"
        );

        assertThat(socialMemberService.findOrCreate(info)).isEqualTo(3L);

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getEmail()).hasSize(50);
        assertThat(captor.getValue().getProviderUserId()).hasSize(255);
    }

    @Test
    @DisplayName("이메일이 51자이면 저장 전에 거부한다")
    void emailOverMaximum_rejected() {
        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, "google-123"
        )).thenReturn(Optional.empty());

        var info = new SocialUserInfo(
                AuthProvider.GOOGLE,
                "google-123",
                "a".repeat(39) + "@example.com",
                true,
                "회원"
        );

        assertThatThrownBy(() -> socialMemberService.findOrCreate(info))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode()
                        ).isEqualTo("INVALID_USER_INFO")
                );

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 51})
    @DisplayName("이모지 닉네임은 문자를 손상시키지 않고 최대 50개까지 저장한다")
    void emojiNickname_preservesCharacters(int length) {
        String nickname = "🎡".repeat(length);

        User savedUser = saveNewMemberWithNickname(nickname);

        assertThat(savedUser.getNickname()).isEqualTo("🎡".repeat(50));
        assertThat(savedUser.getNickname().codePointCount(
                0, savedUser.getNickname().length()
        )).isEqualTo(50);
    }

    @Test
    @DisplayName("공백 닉네임이면 임시 닉네임을 생성한다")
    void blankNickname_generatesTemporaryNickname() {
        User savedUser = saveNewMemberWithNickname("   ");

        assertThat(savedUser.getNickname())
                .startsWith("회원_")
                .hasSizeLessThanOrEqualTo(50);
    }

    private User saveNewMemberWithNickname(String nickname) {
        when(userRepository.findByProviderAndProviderUserId(
                AuthProvider.GOOGLE, "google-new"
        )).thenReturn(Optional.empty());

        when(userRepository.existsByEmail("new@example.com"))
                .thenReturn(false);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    ReflectionTestUtils.setField(user, "id", 4L);
                    return user;
                });

        socialMemberService.findOrCreate(new SocialUserInfo(
                AuthProvider.GOOGLE,
                "google-new",
                "new@example.com",
                true,
                nickname
        ));

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}