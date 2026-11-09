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

//어트랙션 좌표
// 좌표는 추천 경로 기능만 쓰는 데이터라 attractions 테이블이 아닌 별도 테이블에 있다.
// 추천 기능은 읽기만 한다.

@Getter
@Entity
@Immutable //변경 불가
@Table(name = "attraction_locations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attraction_locations_attraction",
                columnNames = "attraction_id")) //중복 방지 -> db가 insert 거부
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttractionLocation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attraction_id", nullable = false)
    private Long attractionId;

    // 위도
    @Column(name = "latitude", nullable = false)
    private Double latitude;

    // 경도
    @Column(name = "longitude", nullable = false)
    private Double longitude;


    //테스트 데이터 생성용 팩토리. 운영 데이터는 SQL 마이그레이션으로만 넣는다.

    public static AttractionLocation of(Long attractionId, double latitude, double longitude) {
        AttractionLocation location = new AttractionLocation();
        location.attractionId = attractionId;
        location.latitude = latitude;
        location.longitude = longitude;
        return location;
    }
}
