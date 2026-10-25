package com.example.attractionreservation.domain.user.service;

import com.example.attractionreservation.domain.user.dto.UpdateUserRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserResponse;
import com.example.attractionreservation.domain.user.dto.GetUserResponse;
import com.example.attractionreservation.domain.user.entity.UserStatus;
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
}
