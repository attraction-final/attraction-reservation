package com.example.attractionreservation.domain.user.service;

import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserStatusResponse;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.dto.SearchUserResponse;
import com.example.attractionreservation.domain.user.dto.UpdatePasswordRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserResponse;
import com.example.attractionreservation.domain.user.dto.GetUserResponse;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.domain.user.dto.CreateUserRequest;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
import com.example.attractionreservation.domain.user.dto.SearchUserCondition;
import com.example.attractionreservation.domain.user.dto.SearchUserListResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;


@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public CreateUserResponse signup(CreateUserRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String encodedPassword = passwordEncoder.encode(request.password());

        User user = User.builder()
                .email(request.email())
                .password(encodedPassword)
                .nickname(request.nickname())
                .build();

        User savedUser;

        try {
            User savedUserResult = userRepository.saveAndFlush(user);
            savedUser = savedUserResult;
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateEmailViolation(exception)) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            throw exception;
        }

        return new CreateUserResponse(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getNickname(),
                savedUser.getRole().name(),
                savedUser.getStatus().name()
        );
    }

    @Transactional(readOnly = true)
    public SearchUserListResponse findUserAll(
            SearchUserCondition condition
    ) {
        // 가입 시각이 같아도 조회 순서가 일정하도록 ID를 보조 정렬 기준으로 사용한다.
        Pageable pageable = PageRequest.of(
                condition.page(),
                condition.size(),
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        return SearchUserListResponse.from(
                userRepository.findAllByStatusNot(
                        UserStatus.WITHDRAWN,
                        pageable
                )
        );
    }

    @Transactional(readOnly = true)
    public SearchUserResponse findUserById(Long userId) {
        // 관리자 상세 조회는 관리 목적의 예외로 탈퇴·정지 회원도 조회한다.
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));

        return SearchUserResponse.from(user);
    }

    @Transactional
    public UpdateUserStatusResponse updateUserStatus(
            Long userId,
            UpdateUserStatusRequest request
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.updateStatus(request.status());

        // Redis 토큰을 삭제하기 전에 DB 수정 쿼리를 실행하여
        // 이 시점에 확인 가능한 DB 오류를 먼저 검출한다. 아직 커밋된 것은 아니다.
        userRepository.flush();

        if (user.getStatus() == UserStatus.SUSPENDED) {
            // 정지 해제 후에도 이전 Refresh Token으로 재발급하지 못하도록 폐기한다.
            // Redis 오류를 그대로 전달하여 DB 상태 변경은 롤백되도록 한다.
            refreshTokenRepository.delete(userId);
        }

        return UpdateUserStatusResponse.from(user);
    }

    @Transactional(readOnly = true)
    public GetUserResponse getMe(Long userId) {
        // 인증된 회원 ID로 본인의 정보를 조회한다.
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.UNAUTHORIZED));

        // 인증 이후 회원 상태가 변경된 경우에도 조회를 차단한다.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return GetUserResponse.from(user);
    }

    @Transactional
    public UpdateUserResponse updateMe(
            Long userId,
            UpdateUserRequest request
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.UNAUTHORIZED));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        user.updateNickname(request.nickname());

        return UpdateUserResponse.from(user);
    }

    @Transactional
    public void updatePassword(Long userId, UpdatePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.UNAUTHORIZED));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // BCrypt는 같은 비밀번호도 해시가 달라질 수 있으므로 matches로 검증한다.
        if (!passwordEncoder.matches(
                request.currentPassword(),
                user.getPassword()
        )) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH);
        }

        // 평문 비밀번호를 저장하지 않고 새로 해시 처리한 값만 반영한다.
        String encodedPassword = passwordEncoder.encode(request.newPassword());
        user.updatePassword(encodedPassword);
    }

    private boolean isDuplicateEmailViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1062
                    && sqlException.getMessage() != null) {

                String message = sqlException.getMessage();

                // 이메일 UNIQUE 제약조건 위반만 중복 이메일 오류로 처리한다.
                return message.contains("uk_users_email")
                        || message.contains("UK6dotkott2kjsp8vw4d0m25fb7");
            }

            cause = cause.getCause();
        }

        return false;
    }
    // 예약에서 사용하는 회원 조회
    public User findByUserId(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
