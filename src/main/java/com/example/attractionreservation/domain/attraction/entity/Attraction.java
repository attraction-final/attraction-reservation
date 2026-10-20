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

    @Enumerated(EnumType.STRING) // // Enum 값을 숫자가 아닌 문자열로 데이터베이스에 저장
    @Column(name = "operation_status", nullable = false, length = 50)
    private OperationStatus operationStatus;

    @Column(name = "deleted_at")
    private Instant deletedAt; // 어트랙션이 삭제 처리된 시각을 저장


    // Attraction Service 등록에 필요
    private Attraction(String name, String description, String location, Integer durationMinutes) {
        this.name = name;
        this.description = description;
        this.location = location;
        this.durationMinutes = durationMinutes;
        this.operationStatus = OperationStatus.OPERATING; // 등록 시 기본값
    }

    public static Attraction create(String name, String description, String location,
                                    Integer durationMinutes) {
        return new Attraction(name, description, location, durationMinutes);
    }

    // Attraction Service 목록조회에 필요
    public boolean isOperating() {
        return this.operationStatus == OperationStatus.OPERATING;
    }

    // Attraction Service 수정에 필요
    public void update(String name, String description, String location, Integer durationMinutes) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (location != null) {
            this.location = location;
        }
        if (durationMinutes != null) {
            this.durationMinutes = durationMinutes;
        }
    }

    // Attraction Service 운영 상태 변경에 필요
    public void changeOperationStatus(OperationStatus operationStatus) {
        this.operationStatus = operationStatus;
    }

    // Attraction Service 삭제에 필요
    public void delete() {
        this.deletedAt = Instant.now();
    }
}
