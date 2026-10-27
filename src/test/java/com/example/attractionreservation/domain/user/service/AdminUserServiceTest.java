package com.example.attractionreservation.domain.user.service;

import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusResponse;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.attractionreservation.domain.user.dto.SearchUserResponse;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.attractionreservation.domain.user.dto.SearchUserCondition;
import com.example.attractionreservation.domain.user.dto.SearchUserListResponse;
import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("탈퇴 회원 제외 조건과 최신순 정렬을 적용하고 회원 정보를 변환한다")
    void findUserAll_validCondition_returnsMemberPage() {
        // given
        User user = User.builder()
                .email("user@example.com")
                .password("encoded-password")
                .nickname("놀이공원회원")
                .role(Role.USER)
                .build();

        ReflectionTestUtils.setField(user, "id", 1L);
        ReflectionTestUtils.setField(
                user,
                "createdAt",
                Instant.parse("2026-10-15T01:00:00Z")
        );

        when(userRepository.findAllByStatusNot(
                eq(UserStatus.WITHDRAWN),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(user),
                PageRequest.of(0, 10),
                1
        ));

        // when
        SearchUserListResponse response =
                userService.findUserAll(new SearchUserCondition(0, 10));

        // then
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(userRepository).findAllByStatusNot(
                eq(UserStatus.WITHDRAWN),
                pageableCaptor.capture()
        );

        Pageable pageable = pageableCaptor.getValue();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        ));

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);

        SearchUserListResponse.Item item = response.content().get(0);

        assertThat(item.id()).isEqualTo(1L);
        assertThat(item.email()).isEqualTo("user@example.com");
        assertThat(item.nickname()).isEqualTo("놀이공원회원");
        assertThat(item.role()).isEqualTo(Role.USER);
        assertThat(item.status()).isEqualTo(UserStatus.ACTIVE);

        // UTC 01시는 한국 시각으로 같은 날 오전 10시다.
        assertThat(item.createdAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 15, 10, 0));

        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("조회 조건을 생략하면 기본 페이지를 적용하고 빈 목록을 반환한다")
    void findUserAll_noMembers_returnsEmptyDefaultPage() {
        // given
        SearchUserCondition condition = new SearchUserCondition(null, null);

        when(userRepository.findAllByStatusNot(
                eq(UserStatus.WITHDRAWN),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(
                List.of(),
                PageRequest.of(0, 10),
                0
        ));

        // when
        SearchUserListResponse response = userService.findUserAll(condition);

        // then
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(userRepository).findAllByStatusNot(
                eq(UserStatus.WITHDRAWN),
                pageableCaptor.capture()
        );

        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @ParameterizedTest
    @EnumSource(UserStatus.class)
    @DisplayName("관리자는 탈퇴·정지 상태를 포함한 회원 상세 정보를 조회할 수 있다")
    void findUserById_existingMember_returnsDetailRegardlessOfStatus(
            UserStatus status
    ) {
        // given
        User user = User.builder()
                .email("detail@example.com")
                .password("encoded-password")
                .nickname("상세조회회원")
                .role(Role.USER)
                .build();

        ReflectionTestUtils.setField(user, "id", 10L);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(
                user,
                "createdAt",
                Instant.parse("2026-10-15T01:00:00Z")
        );
        ReflectionTestUtils.setField(
                user,
                "updatedAt",
                Instant.parse("2026-10-15T03:00:00Z")
        );

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        // when
        SearchUserResponse response = userService.findUserById(10L);

        // then
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.email()).isEqualTo("detail@example.com");
        assertThat(response.nickname()).isEqualTo("상세조회회원");
        assertThat(response.role()).isEqualTo(Role.USER);
        assertThat(response.status()).isEqualTo(status);

        // 생성·수정 시각 모두 UTC에서 한국 시각으로 변환됐는지 확인한다.
        assertThat(response.createdAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 15, 10, 0));
        assertThat(response.updatedAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 15, 12, 0));

        verify(userRepository).findById(10L);
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("조회 대상 회원이 없으면 회원 미존재 오류를 발생시킨다")
    void findUserById_missingMember_throwsNotFound() {
        // given
        when(userRepository.findById(10L))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.findUserById(10L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_FOUND)
                );

        verify(userRepository).findById(10L);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("회원을 정지하면 DB 변경을 반영하고 Refresh Token을 폐기한다")
    void updateUserStatus_suspendMember_deletesRefreshToken() {
        // given
        User user = User.builder()
                .email("status@example.com")
                .password("encoded-password")
                .nickname("상태변경회원")
                .build();

        ReflectionTestUtils.setField(user, "id", 10L);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        // when
        UpdateUserStatusResponse response = userService.updateUserStatus(
                10L,
                new UpdateUserStatusRequest(UserStatus.SUSPENDED)
        );

        // then
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.email()).isEqualTo("status@example.com");
        assertThat(response.nickname()).isEqualTo("상태변경회원");
        assertThat(response.status()).isEqualTo(UserStatus.SUSPENDED);

        // DB 수정 쿼리를 먼저 실행하고 토큰을 폐기하는 순서를 확인한다.
        var inOrder = inOrder(userRepository, refreshTokenRepository);
        inOrder.verify(userRepository).findById(10L);
        inOrder.verify(userRepository).flush();
        inOrder.verify(refreshTokenRepository).delete(10L);

        verifyNoInteractions(passwordEncoder);
    }

    @ParameterizedTest
    @CsvSource({
            "ACTIVE, INACTIVE",
            "INACTIVE, ACTIVE",
            "SUSPENDED, ACTIVE"
    })
    @DisplayName("정지 이외의 허용된 상태 변경에서는 토큰 저장소를 호출하지 않는다")
    void updateUserStatus_otherAllowedTransition_doesNotDeleteToken(
            UserStatus currentStatus,
            UserStatus newStatus
    ) {
        // given
        User user = User.builder()
                .email("status@example.com")
                .password("encoded-password")
                .nickname("상태변경회원")
                .build();

        ReflectionTestUtils.setField(user, "id", 10L);
        ReflectionTestUtils.setField(user, "status", currentStatus);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        // when
        UpdateUserStatusResponse response = userService.updateUserStatus(
                10L,
                new UpdateUserStatusRequest(newStatus)
        );

        // then
        assertThat(response.status()).isEqualTo(newStatus);
        assertThat(user.getStatus()).isEqualTo(newStatus);

        verify(userRepository).flush();
        verifyNoInteractions(refreshTokenRepository, passwordEncoder);
    }

    @Test
    @DisplayName("상태 변경 대상 회원이 없으면 DB 수정과 토큰 삭제를 하지 않는다")
    void updateUserStatus_missingMember_throwsNotFound() {
        // given
        when(userRepository.findById(10L))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.updateUserStatus(
                10L,
                new UpdateUserStatusRequest(UserStatus.SUSPENDED)
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND)
        );

        verify(userRepository).findById(10L);
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(refreshTokenRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "ACTIVE, ACTIVE, USER_STATUS_SAME",
            "INACTIVE, SUSPENDED, USER_INVALID_STATUS_TRANSITION",
            "WITHDRAWN, ACTIVE, USER_INVALID_STATUS_TRANSITION"
    })
    @DisplayName("거부된 상태 변경은 DB 수정과 토큰 삭제를 하지 않는다")
    void updateUserStatus_rejectedTransition_doesNotWrite(
            UserStatus currentStatus,
            UserStatus newStatus,
            ErrorCode expectedError
    ) {
        // given
        User user = User.builder()
                .email("status@example.com")
                .password("encoded-password")
                .nickname("상태변경회원")
                .build();

        ReflectionTestUtils.setField(user, "status", currentStatus);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() -> userService.updateUserStatus(
                10L,
                new UpdateUserStatusRequest(newStatus)
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(expectedError)
        );

        assertThat(user.getStatus()).isEqualTo(currentStatus);
        verify(userRepository).findById(10L);
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("DB 수정 쿼리가 실패하면 Refresh Token을 삭제하지 않는다")
    void updateUserStatus_databaseFailure_doesNotDeleteToken() {
        // given
        User user = User.builder()
                .email("status@example.com")
                .password("encoded-password")
                .nickname("상태변경회원")
                .build();

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("DB 연결 실패");

        doThrow(failure).when(userRepository).flush();

        // when & then
        assertThatThrownBy(() -> userService.updateUserStatus(
                10L,
                new UpdateUserStatusRequest(UserStatus.SUSPENDED)
        )).isSameAs(failure);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    @DisplayName("Redis 토큰 삭제 오류를 숨기지 않고 호출자에게 전달한다")
    void updateUserStatus_redisFailure_propagatesException() {
        // given
        User user = User.builder()
                .email("status@example.com")
                .password("encoded-password")
                .nickname("상태변경회원")
                .build();

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Redis 연결 실패");

        doThrow(failure).when(refreshTokenRepository).delete(10L);

        // when & then
        assertThatThrownBy(() -> userService.updateUserStatus(
                10L,
                new UpdateUserStatusRequest(UserStatus.SUSPENDED)
        )).isSameAs(failure);

        verify(userRepository).flush();
        verify(refreshTokenRepository).delete(10L);
    }
}