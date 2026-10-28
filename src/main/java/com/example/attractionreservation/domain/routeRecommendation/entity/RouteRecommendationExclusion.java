package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
//추천 경로에서 제외된 어트랙션 한 건과 그 이유 저장
//추천 방문 목록만 저장하면 사용자가 선택한 어트랙션이 왜 결과에서 빠졌는지 설명할 수  없어서 만듬
@Getter
@Entity
@Table(name = "route_recommendation_exclusions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRecommendationExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //이 제외 이력이 속한 추천 경로
    //컬럼으로 부모 추천과 연결
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private RouteRecommendation routeRecommendation;

    @Column(name = "attraction_id", nullable = false)
    private Long attractionId;

    //추천 당시의 어트랙션 이름
    @Column(name = "attraction_name_snapshot", nullable = false, length = 100)
    private String attractionNameSnapshot;

    //제외 사유 코드
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    private ExclusionReason reasonCode;

    //당시 문구를 별도로 저장해서 과거 이력의 설명을 유지
    @Column(name = "reason_message", nullable = false, length = 255)
    private String reasonMessage;

    //후보 선정과 경로 탐색 과정에서 결정한 reason을 전달 받는다
    public static RouteRecommendationExclusion of(Long attractionId, String attractionName, ExclusionReason reason) {
        RouteRecommendationExclusion exclusion = new RouteRecommendationExclusion();
        exclusion.attractionId = attractionId;
        exclusion.attractionNameSnapshot = attractionName;
        exclusion.reasonCode = reason;
        exclusion.reasonMessage = reason.getMessage();
        return exclusion;
    }
    //제외 이력이 속한 부모 추천을 연결
    void assignRouteRecommendation(RouteRecommendation recommendation) {
        this.routeRecommendation = recommendation;
    }
}
