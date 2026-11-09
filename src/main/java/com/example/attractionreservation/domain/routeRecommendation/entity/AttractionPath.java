package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;


//어트랙션 간 도보 이동 구간 (실측함)
//한 행 = 출발 어트랙션 → 도착 어트랙션. 방향마다 값이 다를 수 있어 (실측 했을때 실제로 있었다)
// 방향별로 한 행씩 저장되어 있다.
//추천 기능은 이 데이터를 읽기만 한다. @Immutable 이라 실수로 필드를 바꿔도 UPDATE가 나가지 않는다.

@Getter
@Entity
@Immutable
@Table(name = "attraction_paths",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attraction_paths_from_to",
                columnNames = {"from_attraction_id", "to_attraction_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttractionPath extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 출발 어트랙션 ID
    @Column(name = "from_attraction_id", nullable = false)
    private Long fromAttractionId;

    // 도착 어트랙션 ID
    @Column(name = "to_attraction_id", nullable = false)
    private Long toAttractionId;

    // 도보 이동 거리(m, 네이버 지도 도보 길찾기 측정값)
    @Column(name = "distance_m", nullable = false)
    private Integer distanceM;

    // 도보 이동 시간(분, 네이버 지도 도보 길찾기 측정값)
    @Column(name = "move_minutes", nullable = false)
    private Integer moveMinutes;

    // true = 실측이 아닌 근사값
    @Column(name = "is_estimated", nullable = false)
    private boolean estimated;

    // false = 공사 등으로 일시 폐쇄된 구간. 추천 계산에 사용하지 않는다.
    @Column(name = "is_active", nullable = false)
    private boolean active;


    //테스트 데이터 생성용 팩토리. 운영 데이터는 SQL 마이그레이션으로만 넣는다.

    public static AttractionPath of(
            Long fromAttractionId,
            Long toAttractionId,
            int distanceM, int moveMinutes,
            boolean estimated, boolean active
    ) {
        AttractionPath path = new AttractionPath();
        path.fromAttractionId = fromAttractionId;
        path.toAttractionId = toAttractionId;
        path.distanceM = distanceM;
        path.moveMinutes = moveMinutes;
        path.estimated = estimated;
        path.active = active;
        return path;
    }
}
