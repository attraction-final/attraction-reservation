package com.example.attractionreservation.domain.user.dto;

import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record SearchUserListResponse(
        List<Item> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static SearchUserListResponse from(Page<User> users) {
        return new SearchUserListResponse(
                users.getContent().stream()
                        .map(Item::from)
                        .toList(),
                users.getNumber(),
                users.getSize(),
                users.getTotalElements(),
                users.getTotalPages()
        );
    }

    public record Item(
            Long id,
            String email,
            String nickname,
            Role role,
            UserStatus status,
            LocalDateTime createdAt
    ) {

        public static Item from(User user) {
            return new Item(
                    user.getId(),
                    user.getEmail(),
                    user.getNickname(),
                    user.getRole(),
                    user.getStatus(),
                    // 저장된 Instant를 공통 규칙에 따라 한국 시각으로 표현한다.
                    LocalDateTime.ofInstant(
                            user.getCreatedAt(),
                            ZoneId.of("Asia/Seoul")
                    )
            );
        }
    }
}