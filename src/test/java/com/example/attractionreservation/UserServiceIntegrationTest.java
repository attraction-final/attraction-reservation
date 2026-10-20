package com.example.attractionreservation;

import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.domain.user.dto.CreateUserRequest;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void signup_savesUserToDatabase() {
        // given
        CreateUserRequest request = new CreateUserRequest(
                "integration@gmail.com",
                "Password123!",
                "홍길동"
        );

        // when
        CreateUserResponse response = userService.signup(request);

        userRepository.flush();

        // then
        User savedUser = userRepository.findById(response.id())
                .orElseThrow();

        assertThat(savedUser.getEmail())
                .isEqualTo("integration@gmail.com");

        assertThat(savedUser.getNickname())
                .isEqualTo("홍길동");

        assertThat(savedUser.getPassword())
                .isNotEqualTo("Password123!");

        assertThat(passwordEncoder.matches(
                "Password123!",
                savedUser.getPassword()
        )).isTrue();

        assertThat(savedUser.getRole())
                .isEqualTo(Role.USER);

        assertThat(savedUser.getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void signup_duplicateEmail_throwsBusinessException() {
        // given
        CreateUserRequest request = new CreateUserRequest(
                "duplicate@gmail.com",
                "Password123!",
                "홍길동"
        );

        userService.signup(request);

        // when & then
        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }
}