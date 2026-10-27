package com.example.attractionreservation.domain.user.repository;

import com.example.attractionreservation.domain.user.entity.AuthProvider;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    // 제공자와 제공자 회원 ID로 소셜 회원을 식별한다.
    Optional<User> findByProviderAndProviderUserId(
            AuthProvider provider,
            String providerUserId
    );

    // 관리자 목록에서 탈퇴 회원을 제외한다.
    Page<User> findAllByStatusNot(
            UserStatus status,
            Pageable pageable
    );
}