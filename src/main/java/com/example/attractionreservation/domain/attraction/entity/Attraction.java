package com.example.attractionreservation.domain.attraction.entity;

import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
// 삭제되지 않은 어트랙션은 같은 이름을 사용할 수 없도록 DB에서 보장한다.
// 삭제된 행의 active_attraction은 NULL이므로 같은 이름의 삭제 이력을 보존하고 이름을 재사용할 수 있다.
@Table(name = "attractions",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_attraction_name_active",
                    columnNames = {
                            "name",
                            "active_attraction"
                    }
            )
        }
)
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

    // 이름 중복 방지용 보조 컬럼: DB가 deleted_at이 NULL이면 1, 삭제된 행이면 NULL로 계산한다.
    // JPA는 이 값을 직접 저장·수정하지 않으며, name과 묶인 UNIQUE 제약에 사용한다.
    @Column(name = "active_attraction",
            insertable = false,
            updatable = false,
            columnDefinition = """
                    INT GENERATED ALWAYS AS (
                        CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END
                    )
                    """
    )
    private Integer activeAttraction;


    // Attraction Service 등록에 필요
    private Attraction(String name, String description, String location, Integer durationMinutes) {
        this.name = name;
        this.description = description;
        this.location = location;
        this.durationMinutes = durationMinutes;
        this.operationStatus = OperationStatus.OPERATING; // 등록 시 기본값
    }

    public static Attraction create(String name, String description, String location,
                                    Integer durationMinutes, Integer entranceMoveMinutes) {
        Attraction attraction = new Attraction();
        attraction.name = name;
        attraction.description = description;
        attraction.location = location;
        attraction.durationMinutes = durationMinutes;
        attraction.entranceMoveMinutes = entranceMoveMinutes;
        attraction.operationStatus = OperationStatus.OPERATING;
        return attraction;
    }

    // Attraction Service 목록조회에 필요
    public boolean isOperating() {
        return this.operationStatus == OperationStatus.OPERATING;
    }

    // Attraction Service 수정에 필요
    public void update(String name, String description, String location,
                       Integer durationMinutes, Integer entranceMoveMinutes) {
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (location != null) this.location = location;
        if (durationMinutes != null) this.durationMinutes = durationMinutes;
        if (entranceMoveMinutes != null) this.entranceMoveMinutes = entranceMoveMinutes;
    }

    // Attraction Service 운영 상태 변경에 필요
    public void changeOperationStatus(OperationStatus operationStatus) {
        this.operationStatus = operationStatus;
    }

    // Attraction Service 삭제에 필요
    public void deleted() {
        this.operationStatus = OperationStatus.DELETED;
        this.deletedAt = Instant.now();
    }

    // 추가: 정문 → 이 어트랙션까지 예상 이동 시간(분)
    @Column(name = "entrance_move_minutes", nullable = false)
    private Integer entranceMoveMinutes;
}
