package com.example.attractionreservation;

import com.example.attractionreservation.domain.user.dto.UpdatePasswordRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusRequest;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.domain.user.service.UserWithdrawalService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "withdrawal-concurrency-test=true"
})
@ActiveProfiles("test")
class UserWithdrawalConcurrencyIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserWithdrawalService withdrawalService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @ParameterizedTest(name = "{0} 변경과 탈퇴")
    @ValueSource(strings = {"닉네임", "비밀번호", "관리자정지"})
    @DisplayName("회원 변경과 탈퇴가 겹쳐도 상태 변경 규칙을 유지한다")
    void memberChange_overlapsWithdrawal_keepsConsistentStatus(
            String change
    ) throws Exception {
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password(passwordEncoder.encode("Current123!"))
                        .nickname("변경전닉네임")
                        .build()
        );
        Long userId = user.getId();

        CountDownLatch memberRead = new CountDownLatch(1);
        CountDownLatch resumeUpdate = new CountDownLatch(1);
        AtomicReference<Thread> modifyingThread = new AtomicReference<>();

        // 실제 조회를 실행하고 회원 변경 스레드만 조회 직후 멈춘다.
        var advisor = new DefaultPointcutAdvisor(
                (MethodInterceptor) invocation -> {
                    Object result = invocation.proceed();

                    String methodName = invocation.getMethod().getName();
                    boolean memberLookup =
                            methodName.equals("findById")
                                    || methodName.equals("findByIdForUpdate");

                    if (memberLookup
                            && Thread.currentThread() == modifyingThread.get()) {
                        memberRead.countDown();

                        if (!resumeUpdate.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "회원 변경 재개 신호를 받지 못했습니다."
                            );
                        }
                    }

                    return result;
                }
        );

        Advised repositoryProxy = (Advised) userRepository;
        repositoryProxy.addAdvisor(0, advisor);

        var executor = Executors.newFixedThreadPool(2);

        try {
            var updateFuture = executor.submit(() -> {
                modifyingThread.set(Thread.currentThread());
                changeMember(userId, change);
            });

            if (!memberRead.await(10, TimeUnit.SECONDS)) {
                resumeUpdate.countDown();
                updateFuture.get(5, TimeUnit.SECONDS);

                throw new AssertionError(
                        "회원 변경은 종료됐지만 조회 완료 신호가 없습니다."
                );
            }

            var withdrawalFuture = executor.submit(() -> {
                try {
                    withdrawalService.withdraw(userId);
                    return true;
                } catch (BusinessException exception) {
                    if (exception.getErrorCode()
                            == ErrorCode.USER_NOT_WITHDRAWABLE) {
                        return false;
                    }
                    throw exception;
                }
            });

            try {
                withdrawalFuture.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                // 시간 초과 자체를 성공으로 판단하지 않는다.
                // 두 작업이 끝난 뒤 업무 결과와 DB 상태를 확인한다.
            } finally {
                resumeUpdate.countDown();
            }

            updateFuture.get(10, TimeUnit.SECONDS);
            boolean withdrawn = withdrawalFuture.get(10, TimeUnit.SECONDS);

            User savedUser = userRepository.findById(userId)
                    .orElseThrow();

            if (change.equals("관리자정지")) {
                // 정지가 먼저 저장됐으므로 ACTIVE 전용 탈퇴는 거부돼야 한다.
                assertThat(withdrawn).isFalse();
                assertThat(savedUser.getStatus())
                        .isEqualTo(UserStatus.SUSPENDED);
                assertThat(savedUser.getWithdrawnAt()).isNull();
            } else {
                assertThat(withdrawn).isTrue();
                assertThat(savedUser.getStatus())
                        .isEqualTo(UserStatus.WITHDRAWN);
                assertThat(savedUser.getWithdrawnAt()).isNotNull();
            }

            if (change.equals("닉네임")) {
                assertThat(savedUser.getNickname())
                        .isEqualTo("변경후닉네임");
            }

            if (change.equals("비밀번호")) {
                assertThat(passwordEncoder.matches(
                        "Changed123!",
                        savedUser.getPassword()
                )).isTrue();
            }
        } finally {
            resumeUpdate.countDown();
            executor.shutdownNow();

            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } finally {
                repositoryProxy.removeAdvisor(advisor);
            }
        }
    }

    private void changeMember(Long userId, String change) {
        switch (change) {
            case "닉네임" -> userService.updateMe(
                    userId,
                    new UpdateUserRequest("변경후닉네임")
            );
            case "비밀번호" -> userService.updatePassword(
                    userId,
                    new UpdatePasswordRequest(
                            "Current123!",
                            "Changed123!"
                    )
            );
            case "관리자정지" -> userService.updateUserStatus(
                    1L,
                    userId,
                    new UpdateUserStatusRequest(UserStatus.SUSPENDED)
            );
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 회원 변경입니다."
            );
        }
    }
}