package com.example.attractionreservation.domain.user.repository;

import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    // 관리자 목록에서도 탈퇴 회원을 기본 조회 대상에서 제외한다.
    Page<User> findAllByStatusNot(
            UserStatus status,
            Pageable pageable
    );
}