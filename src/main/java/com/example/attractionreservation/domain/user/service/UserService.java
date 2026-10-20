package com.example.attractionreservation.domain.user.service;

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
                savedUser.getNickname()
        );
    }

    private boolean isDuplicateEmailViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1062
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().contains("email")) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}