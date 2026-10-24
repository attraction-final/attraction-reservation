package com.example.attractionreservation.domain.user.service;

import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.dto.CreateUserRequest;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;

import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserService(
                userRepository,
                passwordEncoder,
                refreshTokenRepository
        );
    }

    @Test
    void signup_success() {
        // given
        CreateUserRequest request = new CreateUserRequest(
                "test@gmail.com",
                "Password123!",
                "홍길동"
        );

        when(userRepository.existsByEmail(request.email()))
                .thenReturn(false);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        CreateUserResponse response = userService.signup(request);

        // then
        // then
        assertThat(response.email()).isEqualTo("test@gmail.com");
        assertThat(response.nickname()).isEqualTo("홍길동");

        verify(userRepository).existsByEmail("test@gmail.com");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertThat(savedUser.getPassword()).isNotEqualTo(request.password());
        assertThat(passwordEncoder.matches(request.password(), savedUser.getPassword()))
                .isTrue();
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        // given
        CreateUserRequest request = new CreateUserRequest(
                "test@gmail.com",
                "Password123!",
                "홍길동"
        );

        when(userRepository.existsByEmail(request.email()))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
                });

        verify(userRepository).existsByEmail("test@gmail.com");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void signup_databaseDuplicateEmail_throwsBusinessException() {
        // given
        CreateUserRequest request = new CreateUserRequest(
                "duplicate@gmail.com",
                "Password123!",
                "홍길동"
        );

        SQLException sqlException = new SQLException(
                "Duplicate entry 'duplicate@gmail.com' for key 'uk_users_email'",
                "23000",
                1062
        );

        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("Duplicate email", sqlException);

        when(userRepository.existsByEmail(request.email()))
                .thenReturn(false);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(exception);

        // when & then
        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException businessException = (BusinessException) thrown;
                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
                });
    }

    @Test
    void signup_otherDatabaseViolation_rethrowsOriginalException() {
        // given
        CreateUserRequest request = new CreateUserRequest(
                "test@gmail.com",
                "Password123!",
                "홍길동"
        );

        SQLException sqlException = new SQLException(
                "Cannot add or update a child row: a foreign key constraint fails",
                "23000",
                1452
        );

        DataIntegrityViolationException exception =
                new DataIntegrityViolationException(
                        "Foreign key violation",
                        sqlException
                );

        when(userRepository.existsByEmail(request.email()))
                .thenReturn(false);

        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(exception);

        // when & then
        assertThatThrownBy(() -> userService.signup(request))
                .isSameAs(exception);
    }
}