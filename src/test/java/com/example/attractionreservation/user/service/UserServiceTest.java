package com.example.attractionreservation.user.service;

import com.example.attractionreservation.domain.user.dto.UpdateUserRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserResponse;
import com.example.attractionreservation.domain.user.dto.CreateUserRequest;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.dto.GetUserResponse;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserService(userRepository, passwordEncoder);
    }

    @Test
    void signup_success() {
        CreateUserRequest request = new CreateUserRequest(
                "test@gmail.com", "Password123!", "홍길동"
        );

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateUserResponse response = userService.signup(request);

        assertThat(response.email()).isEqualTo("test@gmail.com");
        assertThat(response.nickname()).isEqualTo("홍길동");

        verify(userRepository).existsByEmail("test@gmail.com");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertThat(savedUser.getPassword()).isNotEqualTo(request.password());
        assertThat(passwordEncoder.matches(
                request.password(), savedUser.getPassword()
        )).isTrue();
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        CreateUserRequest request = new CreateUserRequest(
                "test@gmail.com", "Password123!", "홍길동"
        );

        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.DUPLICATE_EMAIL)
                );

        verify(userRepository).existsByEmail("test@gmail.com");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void signup_databaseDuplicateEmail_throwsBusinessException() {
        CreateUserRequest request = new CreateUserRequest(
                "duplicate@gmail.com", "Password123!", "홍길동"
        );

        SQLException sqlException = new SQLException(
                "Duplicate entry 'duplicate@gmail.com' for key 'uk_users_email'",
                "23000",
                1062
        );

        DataIntegrityViolationException exception =
                new DataIntegrityViolationException(
                        "Duplicate email", sqlException
                );

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(exception);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        thrown -> assertThat(thrown.getErrorCode())
                                .isEqualTo(ErrorCode.DUPLICATE_EMAIL)
                );
    }

    @Test
    void signup_otherDatabaseViolation_rethrowsOriginalException() {
        CreateUserRequest request = new CreateUserRequest(
                "test@gmail.com", "Password123!", "홍길동"
        );

        SQLException sqlException = new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails",
                "23000",
                1452
        );

        DataIntegrityViolationException exception =
                new DataIntegrityViolationException(
                        "Foreign key violation", sqlException
                );

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(exception);

        assertThatThrownBy(() -> userService.signup(request))
                .isSameAs(exception);
    }

    @Test
    void getMe_activeUser_returnsUserInfo() {
        Long userId = 1L;
        User user = User.builder()
                .email("test@gmail.com")
                .password("encoded-password")
                .nickname("홍길동")
                .build();

        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        GetUserResponse response = userService.getMe(userId);

        assertThat(response).isEqualTo(new GetUserResponse(
                userId, "test@gmail.com", "홍길동", "USER", "ACTIVE"
        ));

        verify(userRepository).findById(userId);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void getMe_missingUser_throwsUnauthorized() {
        Long userId = 1L;

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMe(userId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED)
                );
    }

    @Test
    void getMe_withdrawnUser_throwsUnauthorized() {
        Long userId = 1L;
        User user = User.builder()
                .email("withdrawn@gmail.com")
                .password("encoded-password")
                .nickname("탈퇴회원")
                .build();

        ReflectionTestUtils.setField(user, "status", UserStatus.WITHDRAWN);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.getMe(userId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED)
                );
    }

    @Test
    void updateMe_activeUser_updatesOnlyNickname() {
        Long userId = 1L;
        User user = User.builder()
                .email("test@gmail.com")
                .password("encoded-password")
                .nickname("기존닉네임")
                .build();

        ReflectionTestUtils.setField(user, "id", userId);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        UpdateUserResponse response = userService.updateMe(
                userId,
                new UpdateUserRequest("새로운닉네임")
        );

        assertThat(response).isEqualTo(new UpdateUserResponse(
                userId,
                "test@gmail.com",
                "새로운닉네임"
        ));
        assertThat(user.getNickname()).isEqualTo("새로운닉네임");
        assertThat(user.getEmail()).isEqualTo("test@gmail.com");
        assertThat(user.getPassword()).isEqualTo("encoded-password");
        assertThat(user.getRole().name()).isEqualTo("USER");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);

        verify(userRepository).findById(userId);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void updateMe_missingUser_throwsUnauthorized() {
        Long userId = 1L;

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateMe(
                userId,
                new UpdateUserRequest("새로운닉네임")
        ))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED)
                );
    }

    @Test
    void updateMe_withdrawnUser_throwsUnauthorized() {
        Long userId = 1L;
        User user = User.builder()
                .email("withdrawn@gmail.com")
                .password("encoded-password")
                .nickname("기존닉네임")
                .build();

        ReflectionTestUtils.setField(user, "status", UserStatus.WITHDRAWN);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateMe(
                userId,
                new UpdateUserRequest("새로운닉네임")
        ))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNAUTHORIZED)
                );

        assertThat(user.getNickname()).isEqualTo("기존닉네임");
    }
}