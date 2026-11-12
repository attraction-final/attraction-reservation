package com.example.attractionreservation.domain.user.repository;

import com.example.attractionreservation.domain.user.entity.AuthProvider;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    // 제공자와 제공자 회원 ID로 소셜 회원을 식별한다.
    Optional<User> findByProviderAndProviderUserId(
            AuthProvider provider,
            String providerUserId
    );

    // 같은 회원의 탈퇴 요청 및 같은 회원의 예약을 순서대로 처리하도록 회원 행을 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    // 관리자 목록에서 탈퇴 회원을 제외한다.
    Page<User> findAllByStatusNot(
            UserStatus status,
            Pageable pageable
    );
}