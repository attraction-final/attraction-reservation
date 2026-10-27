package com.example.attractionreservation.domain.user.entity;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_users_email",
                        columnNames = "email"
                ),
                @UniqueConstraint(
                        name = "uk_users_provider_user_id",
                        columnNames = {"provider", "provider_user_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String email;

    // 소셜 회원은 비밀번호를 저장하지 않는다.
    @Column
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    // 회원의 가입 제공자를 구분한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    // 제공자가 발급한 고유 회원 ID이며, 일반 회원은 사용하지 않는다.
    @Column(name = "provider_user_id", length = 255)
    private String providerUserId;

    @Builder
    private User(String email, String password, String nickname, Role role) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.role = (role != null) ? role : Role.USER;
        this.status = UserStatus.ACTIVE;

        // 기존 이메일·비밀번호 가입은 일반 회원으로 구분한다.
        this.provider = AuthProvider.LOCAL;
        this.providerUserId = null;
    }

    public static User createSocial(
            String email,
            String nickname,
            AuthProvider provider,
            String providerUserId
    ) {
        // 일반 가입 경로와 혼동하거나 식별 정보 없이 생성하는 것을 방지한다.
        if (provider == null || provider == AuthProvider.LOCAL) {
            throw new IllegalArgumentException(
                    "소셜 회원의 제공자는 KAKAO 또는 GOOGLE이어야 합니다."
            );
        }

        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException(
                    "소셜 회원의 제공자 회원 ID는 필수입니다."
            );
        }

        // 신규 소셜 회원은 비밀번호 없이 USER 권한과 ACTIVE 상태로 생성한다.
        User user = new User(email, null, nickname, Role.USER);
        user.provider = provider;
        user.providerUserId = providerUserId;
        return user;
    }

    public void updateStatus(UserStatus newStatus) {
        if (this.status == newStatus) {
            throw new BusinessException(ErrorCode.USER_STATUS_SAME);
        }

        // 관리자 상태 변경에서는 본인 탈퇴 절차를 우회할 수 없도록 전이를 제한한다.
        boolean canChange = switch (this.status) {
            case ACTIVE ->
                    newStatus == UserStatus.INACTIVE
                            || newStatus == UserStatus.SUSPENDED;
            case INACTIVE, SUSPENDED ->
                    newStatus == UserStatus.ACTIVE;
            case WITHDRAWN -> false;
        };

        if (!canChange) {
            throw new BusinessException(
                    ErrorCode.USER_INVALID_STATUS_TRANSITION
            );
        }

        this.status = newStatus;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
