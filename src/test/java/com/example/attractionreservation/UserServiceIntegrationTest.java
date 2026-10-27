package com.example.attractionreservation;

import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.domain.user.dto.CreateUserRequest;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @MockitoSpyBean
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

    @Test
    void signup_duplicateEmail_directInsert_throwsDataIntegrityViolationException() {
        // given
        User firstUser = User.builder()
                .email("constraint@gmail.com")
                .password(passwordEncoder.encode("Password123!"))
                .nickname("첫 번째 사용자")
                .build();

        User duplicateUser = User.builder()
                .email("constraint@gmail.com")
                .password(passwordEncoder.encode("Password456!"))
                .nickname("두 번째 사용자")
                .build();

        userRepository.saveAndFlush(firstUser);

        // when & then
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicateUser))
                .isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class
                );
    }
    @Test
    @DisplayName("사전 검사를 통과한 뒤 실제 DB에서 이메일 중복이 발생하면 중복 이메일 오류로 변환한다.")
    void signup_databaseDuplicateEmail_throwsDuplicateEmail() {
        // given
        String email = UUID.randomUUID() + "@example.com";

        CreateUserRequest request = new CreateUserRequest(
                email,
                "Password123!",
                "중복검증"
        );

        userService.signup(request);

        // 두 요청이 사전 검사를 모두 통과한 상황을 재현한다.
        // 저장은 대체하지 않으므로 실제 MySQL UNIQUE 오류가 발생한다.
        doReturn(false).when(userRepository).existsByEmail(email);

        // when & then
        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.DUPLICATE_EMAIL)
                );

        assertThat(userRepository.findAll())
                .filteredOn(user -> email.equals(user.getEmail()))
                .hasSize(1);
    }
}
