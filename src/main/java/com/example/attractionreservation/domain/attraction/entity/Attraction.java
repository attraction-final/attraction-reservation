package com.example.attractionreservation.domain.attraction.entity;

import com.example.attractionreservation.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(name = "attractions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attraction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "location", nullable = false, length = 50)
    private String location;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    @Enumerated(EnumType.STRING) // // Enum 값을 숫자가 아닌 문자열로 데이터베이스에 저장한다.
    @Column(name = "operation_status", nullable = false, length = 50)
    private OperationStatus operationStatus;

    @Column(name = "deleted_at")
    private Instant deletedAt; // 어트랙션이 삭제 처리된 시각을 저장한다.
}
