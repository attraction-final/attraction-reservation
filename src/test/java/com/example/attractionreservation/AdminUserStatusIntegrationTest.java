package com.example.attractionreservation;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusResponse;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "admin-user-status-test=true"
})
@ActiveProfiles("test")
class AdminUserStatusIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("회원 정지를 DB에 저장하고 실제 Redis의 Refresh Token을 폐기한다")
    void updateUserStatus_suspendMember_persistsStatusAndDeletesToken() {
        // given
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("정지통합테스트")
                        .build()
        );

        Long userId = user.getId();
        String refreshToken = jwtProvider.createRefreshToken(userId);

        try {
            refreshTokenRepository.save(userId, refreshToken);
            assertThat(refreshTokenRepository.matches(userId, refreshToken))
                    .isTrue();

            // when
            UpdateUserStatusResponse response = userService.updateUserStatus(
                    userId,
                    new UpdateUserStatusRequest(UserStatus.SUSPENDED)
            );

            // then: 서비스 트랜잭션 종료 후 DB를 다시 조회한다.
            User savedUser = userRepository.findById(userId).orElseThrow();

            assertThat(response.id()).isEqualTo(userId);
            assertThat(response.status()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(savedUser.getStatus()).isEqualTo(UserStatus.SUSPENDED);

            assertThat(refreshTokenRepository.matches(userId, refreshToken))
                    .isFalse();
            assertThat(redisTemplate.hasKey("auth:refresh:" + userId))
                    .isFalse();
        } finally {
            // 테스트에 별도 트랜잭션이 없고 Redis도 자동 롤백되지 않아 직접 정리한다.
            redisTemplate.delete("auth:refresh:" + userId);
            userRepository.deleteById(userId);
        }
    }

    @Test
    @DisplayName("Redis 토큰 삭제가 실패하면 DB의 회원 상태 변경을 롤백한다")
    void updateUserStatus_redisFailure_rollsBackDatabaseStatus() {
        // given
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("롤백통합테스트")
                        .build()
        );

        Long userId = user.getId();
        String refreshToken = jwtProvider.createRefreshToken(userId);

        try {
            refreshTokenRepository.save(userId, refreshToken);
            assertThat(refreshTokenRepository.matches(userId, refreshToken))
                    .isTrue();

            DataAccessResourceFailureException failure =
                    new DataAccessResourceFailureException(
                            "테스트용 Redis 삭제 실패"
                    );

            // 실제 Redis를 중단하지 않고 삭제 호출에만 오류를 발생시킨다.
            doThrow(failure)
                    .when(refreshTokenRepository)
                    .delete(userId);

            // when & then
            assertThatThrownBy(() -> userService.updateUserStatus(
                    userId,
                    new UpdateUserStatusRequest(UserStatus.SUSPENDED)
            )).isSameAs(failure);

            // 서비스 트랜잭션이 종료된 뒤 다시 조회하여 실제 롤백을 확인한다.
            User savedUser = userRepository.findById(userId).orElseThrow();

            assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);

            // 이번 테스트는 삭제 실행 전에 실패하도록 구성했으므로 토큰이 남아 있다.
            assertThat(refreshTokenRepository.matches(userId, refreshToken))
                    .isTrue();
        } finally {
            // 오류를 설정한 저장소 메서드 대신 Redis에 직접 접근하여 정리한다.
            redisTemplate.delete("auth:refresh:" + userId);
            userRepository.deleteById(userId);
        }
    }
}