package com.example.attractionreservation;

import com.example.attractionreservation.domain.user.dto.SearchUserCondition;
import com.example.attractionreservation.domain.user.dto.SearchUserListResponse;
import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "admin-user-list-test=true"
})
@ActiveProfiles("test")
@Transactional
class AdminUserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("탈퇴 회원을 제외하고 가입 최신순과 ID 내림차순으로 페이징 조회한다")
    void findUserAll_mixedMembers_excludesWithdrawnAndPaginates() {
        // given
        Long olderId = saveUser(
                Role.USER,
                UserStatus.ACTIVE,
                Instant.parse("2026-10-14T01:00:00Z")
        );

        Instant sameCreatedAt = Instant.parse("2026-10-15T01:00:00Z");

        Long adminId = saveUser(
                Role.ADMIN,
                UserStatus.INACTIVE,
                sameCreatedAt
        );

        Long suspendedId = saveUser(
                Role.USER,
                UserStatus.SUSPENDED,
                sameCreatedAt
        );

        saveUser(
                Role.USER,
                UserStatus.WITHDRAWN,
                Instant.parse("2026-10-16T01:00:00Z")
        );

        entityManager.clear();

        // when
        SearchUserListResponse firstPage =
                userService.findUserAll(new SearchUserCondition(0, 2));
        SearchUserListResponse secondPage =
                userService.findUserAll(new SearchUserCondition(1, 2));

        // then
        assertThat(firstPage.content())
                .extracting(SearchUserListResponse.Item::id)
                .containsExactly(suspendedId, adminId);

        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(2);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);

        // 권한이나 비활성·정지 상태만으로 목록에서 제외하지 않는다.
        assertThat(firstPage.content().get(0).status())
                .isEqualTo(UserStatus.SUSPENDED);
        assertThat(firstPage.content().get(1).role())
                .isEqualTo(Role.ADMIN);
        assertThat(firstPage.content().get(1).status())
                .isEqualTo(UserStatus.INACTIVE);

        assertThat(secondPage.content())
                .extracting(SearchUserListResponse.Item::id)
                .containsExactly(olderId);
        assertThat(secondPage.page()).isEqualTo(1);
        assertThat(secondPage.totalElements()).isEqualTo(3);
        assertThat(secondPage.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("탈퇴 회원만 있으면 빈 목록과 전체 회원 수 0을 반환한다")
    void findUserAll_onlyWithdrawnMember_returnsEmptyPage() {
        // given
        saveUser(
                Role.USER,
                UserStatus.WITHDRAWN,
                Instant.parse("2026-10-15T01:00:00Z")
        );

        entityManager.clear();

        // when
        SearchUserListResponse response =
                userService.findUserAll(new SearchUserCondition(null, null));

        // then
        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    private Long saveUser(
            Role role,
            UserStatus status,
            Instant createdAt
    ) {
        User user = User.builder()
                .email(UUID.randomUUID() + "@test.com")
                .password("test-password-hash")
                .nickname("관리자조회테스트")
                .role(role)
                .build();

        // 운영용 상태 변경 기능과 별개로 조회 조건에 필요한 테스트 데이터를 만든다.
        ReflectionTestUtils.setField(user, "status", status);
        userRepository.saveAndFlush(user);

        Long userId = user.getId();

        // 가입 시각이 같은 상황을 정확하게 재현하도록 테스트에서만 시각을 조정한다.
        entityManager.createQuery("""
                UPDATE User u
                SET u.createdAt = :createdAt
                WHERE u.id = :userId
                """)
                .setParameter("createdAt", createdAt)
                .setParameter("userId", userId)
                .executeUpdate();

        return userId;
    }
}