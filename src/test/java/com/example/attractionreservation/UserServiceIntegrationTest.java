package com.example.attractionreservation;

import com.example.attractionreservation.user.service.UserService;
import com.example.attractionreservation.user.dto.UserSignupRequest;
import com.example.attractionreservation.user.dto.UserSignupResponse;
import com.example.attractionreservation.user.entity.User;
import com.example.attractionreservation.user.entity.UserRole;
import com.example.attractionreservation.user.entity.UserStatus;
import com.example.attractionreservation.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional
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
        UserSignupRequest request = new UserSignupRequest(
                "integration@gmail.com",
                "Password123!",
                "홍길동"
        );

        // when
        UserSignupResponse response = userService.signup(request);

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
                .isEqualTo(UserRole.USER);

        assertThat(savedUser.getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }
}