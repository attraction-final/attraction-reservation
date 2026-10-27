package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.StartLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.TravelTimeBasis;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Getter
@Entity
@Table(name = "route_recommendations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRecommendation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ticket_id", nullable = false) //임시로 ID참조
    private Long ticketId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name ="start_location", nullable = false,length = 30)
    private StartLocation startLocation;

    @Enumerated(EnumType.STRING)
    @Column(name ="travel_time_basis", nullable = false, length = 30)
    private TravelTimeBasis travelTimeBasis;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false, length = 20)
    private RouteResultType resultType;

    @Column(name = "total_selected_attractions", nullable = false)
    private int totalSelectedAttractions;

    @Column(name = "total_move_minutes", nullable = false)
    private int totalMoveMinutes;

    @Column(name = "total_wait_minutes", nullable = false)
    private int totalWaitMinutes;

    @Column(name = "total_ride_minutes", nullable = false)
    private int totalRideMinutes;

    @Column(name = "total_duration_minutes", nullable = false)
    private int totalDurationMinutes;

    @Column(name = "estimated_end_at", nullable = false)
    private LocalDateTime estimatedEndAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

   @OneToMany(mappedBy = "routeRecommendation", cascade = CascadeType.ALL, orphanRemoval = true)
   @OrderBy("sequenceNumber ASC")
    private List<RouteRecommendationStop> stops = new ArrayList<>();

   @OneToMany(mappedBy = "routeRecommendation", cascade =  CascadeType.ALL, orphanRemoval = true)
    private List<RouteRecommendationExclusion> exclusions = new ArrayList<>();

   public boolean isOwnedBy(Long userId) {
       return this.userId.equals(userId);
   }

   public static RouteRecommendation create(
           Long userId,
           Long ticketId,
           LocalDateTime startAt,
           LocalDateTime endAt,
           List<RouteRecommendationStop> stops,
           List<RouteRecommendationExclusion> exclusions
   ) {
       if (stops.isEmpty()) {
           throw new IllegalArgumentException("방문지가 없는 추천은 저장하지 않습니다.");
       }
       RouteRecommendation recommendation = new RouteRecommendation();
       recommendation.userId = userId;
       recommendation.ticketId = ticketId;
       recommendation.startAt = startAt;
       recommendation.endAt = endAt;
       recommendation.startLocation = StartLocation.ENTRANCE;
       recommendation.travelTimeBasis =TravelTimeBasis.ENTRANCE_FIXED;

       stops.stream()
               .sorted(Comparator.comparing(RouteRecommendationStop::getSequenceNumber))
               .forEach(recommendation::addStop);
       exclusions.forEach(recommendation::addExclusion);

       recommendation.summarize();
       return recommendation;
   }

   private void addStop(RouteRecommendationStop stop) {
       stops.add(stop);
       stop.assignRouteRecommendation(this);
   }
   private void addExclusion(RouteRecommendationExclusion exclusion) {
       exclusions.add(exclusion);
       exclusion.assignRouteRecommendation(this);
   }
   private void summarize() {
       RouteRecommendationStop lastStop = stops.get(stops.size() - 1);

       this.totalSelectedAttractions = stops.size() + exclusions.size();
       this.resultType = exclusions.isEmpty() ? RouteResultType.FULL :RouteResultType.PARTIAL;
       this.totalMoveMinutes = stops.stream().mapToInt(RouteRecommendationStop::getEstimatedMoveMin).sum();
       this.totalWaitMinutes = stops.stream().mapToInt(RouteRecommendationStop::getEstimatedWaitMin).sum();
       this.totalRideMinutes = stops.stream().mapToInt(RouteRecommendationStop::getEstimatedRideMin).sum();
       this.estimatedEndAt =lastStop.getSlotEndAt();
       this.totalDurationMinutes = (int) Duration.between(startAt.atZone(KstTime.ZONE), estimatedEndAt.atZone(KstTime.ZONE)).toMinutes();
   }
    public boolean isDeleted() {
        return deletedAt != null;
    }
    public void delete(Instant now) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = now;
    }




}
