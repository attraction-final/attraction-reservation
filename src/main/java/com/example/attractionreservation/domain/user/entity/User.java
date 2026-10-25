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

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Builder
    private User(String email, String password, String nickname, Role role) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.role = (role != null) ? role : Role.USER;
        this.status = UserStatus.ACTIVE;
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
}
