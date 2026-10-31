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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "admin-user-status-test=true"
})

@ExtendWith(OutputCaptureExtension.class)
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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    @DisplayName("회원 정지를 DB에 저장하고 실제 Redis의 Refresh Token을 폐기한다")
    void updateUserStatus_suspendMember_persistsStatusAndDeletesToken(
            CapturedOutput output
    ) {
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
                    1L,
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
            assertThat(output.getAll()).contains(
                    "event=USER_STATUS_CHANGED result=SUCCESS "
                            + "adminId=1 userId=" + userId
                            + " previousStatus=ACTIVE currentStatus=SUSPENDED"
            );
        } finally {
            // 테스트에 별도 트랜잭션이 없고 Redis도 자동 롤백되지 않아 직접 정리한다.
            redisTemplate.delete("auth:refresh:" + userId);
            userRepository.deleteById(userId);
        }
    }

    @Test
    @DisplayName("Redis 토큰 삭제가 실패하면 DB의 회원 상태 변경을 롤백한다")
    void updateUserStatus_redisFailure_rollsBackDatabaseStatus(
            CapturedOutput output
    ) {
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
                    1L,
                    userId,
                    new UpdateUserStatusRequest(UserStatus.SUSPENDED)
            )).isSameAs(failure);

            // 서비스 트랜잭션이 종료된 뒤 다시 조회하여 실제 롤백을 확인한다.
            User savedUser = userRepository.findById(userId).orElseThrow();

            assertThat(savedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);

            // 이번 테스트는 삭제 실행 전에 실패하도록 구성했으므로 토큰이 남아 있다.
            assertThat(refreshTokenRepository.matches(userId, refreshToken))
                    .isTrue();
            assertThat(output.getAll())
                    .doesNotContain("event=USER_STATUS_CHANGED");
        } finally {
            // 오류를 설정한 저장소 메서드 대신 Redis에 직접 접근하여 정리한다.
            redisTemplate.delete("auth:refresh:" + userId);
            userRepository.deleteById(userId);
        }
    }

    @Test
    @DisplayName("이벤트 전달 후 외부 트랜잭션이 롤백되면 완료 로그를 남기지 않는다")
    void updateUserStatus_outerRollback_doesNotLogSuccess(
            CapturedOutput output
    ) {
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("로그롤백테스트")
                        .build()
        );

        Long userId = user.getId();

        try {
            TransactionTemplate transaction =
                    new TransactionTemplate(transactionManager);

            transaction.executeWithoutResult(status -> {
                userService.updateUserStatus(
                        1L,
                        userId,
                        new UpdateUserStatusRequest(UserStatus.INACTIVE)
                );

                // 서비스가 반환돼도 외부 트랜잭션이 커밋되기 전에는
                // 완료 로그가 출력되면 안 된다.
                assertThat(output.getAll())
                        .doesNotContain("event=USER_STATUS_CHANGED");

                // 이벤트 전달 이후의 롤백을 의도적으로 발생시킨다.
                status.setRollbackOnly();
            });

            User savedUser = userRepository.findById(userId)
                    .orElseThrow();

            assertThat(savedUser.getStatus())
                    .isEqualTo(UserStatus.ACTIVE);

            assertThat(output.getAll())
                    .doesNotContain("event=USER_STATUS_CHANGED");
        } finally {
            userRepository.deleteById(userId);
        }
    }
}