package com.example.attractionreservation.user.service;

import com.example.attractionreservation.user.dto.UserSignupRequest;
import com.example.attractionreservation.user.dto.UserSignupResponse;
import com.example.attractionreservation.user.entity.User;
import com.example.attractionreservation.user.repository.UserRepository;
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
        // given
        UserSignupRequest request = new UserSignupRequest(
                "test@gmail.com",
                "Password123!",
                "홍길동"
        );

        when(userRepository.existsByEmail(request.email()))
                .thenReturn(false);

        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when
        UserSignupResponse response = userService.signup(request);

        // then
        // then
        assertThat(response.email()).isEqualTo("test@gmail.com");
        assertThat(response.nickname()).isEqualTo("홍길동");

        verify(userRepository).existsByEmail("test@gmail.com");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertThat(savedUser.getPassword()).isNotEqualTo(request.password());
        assertThat(passwordEncoder.matches(request.password(), savedUser.getPassword()))
                .isTrue();
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        // given
        UserSignupRequest request = new UserSignupRequest(
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
        verify(userRepository, never()).save(any(User.class));
    }
}