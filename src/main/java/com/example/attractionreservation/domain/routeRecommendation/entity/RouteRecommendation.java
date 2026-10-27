package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.StartLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.TravelTimeBasis;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
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
//추천 경로 한건의 요청 조건, 계산 결과 요약, 방문,제외 목록을 저장
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

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    //추천 계산에 실제로 사용한 이용 시작 시각
    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    //사용자가 정한 이용 종료 시각
    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    //추천 계산의 출발위치 -> 지금은 정문으로 고정됨
    @Enumerated(EnumType.STRING)
    @Column(name ="start_location", nullable = false,length = 30)
    private StartLocation startLocation;

    //추천 계산에 적용한 이동 시간 기준 ->지금은 각 어트랙션의 정문 기준 이동 시간을 사용하는 ENTRANCE_FIXED로 고정함
    @Enumerated(EnumType.STRING)
    @Column(name ="travel_time_basis", nullable = false, length = 30)
    private TravelTimeBasis travelTimeBasis;

    //저장된 추천이 전체 추천인지 부분 추천인지 나타냄
    //방문 일정이 비어 있는  NONE은 create()에서 생성하지 않음
    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false, length = 20)
    private RouteResultType resultType;

    //사용자가 선택한 전체 어트랙션 수 -> 추천에 포함된 방문 + 제외된 어트랙션 수로 계산
    // !!!!! 서비스가 선택한 각 어트랙션을 방문 or 제외 목록 중 한 곳에 정확히 한 번씩 넣어야될듯함!
    @Column(name = "total_selected_attractions", nullable = false)
    private int totalSelectedAttractions;

    //오든 방문 일정의 예상 이동 시간 합계
    @Column(name = "total_move_minutes", nullable = false)
    private int totalMoveMinutes;

    //모든 방문 일정의 예상 대기 시간 합계
    @Column(name = "total_wait_minutes", nullable = false)
    private int totalWaitMinutes;

    //모든 방문 일정의 예상 탑승 시간 합계
    @Column(name = "total_ride_minutes", nullable = false)
    private int totalRideMinutes;

    //이용 시작부터 마지막 탑승 종료까지의 전체 소요 시간 ->startAt과 estimatedEndAt 사이의 시간 차이로 계산함
    @Column(name = "total_duration_minutes", nullable = false)
    private int totalDurationMinutes;

    //추천 경로의 마지막 방문 일정이 끝나는 예상 시각 ->방문 순서상 마지막 stop의 slotEndAt을 사용함
    @Column(name = "estimated_end_at", nullable = false)
    private LocalDateTime estimatedEndAt;

    //추천 이력을 소프트 삭제한 시각 -> null ->삭제되지 않은 이력 , 값이 있음 -> 삭제된 이력
    @Column(name = "deleted_at")
    private Instant deletedAt;

    //이 추천 경로에 포함된 방문 일정 목록
   @OneToMany(mappedBy = "routeRecommendation", cascade = CascadeType.ALL, orphanRemoval = true)
   @OrderBy("sequenceNumber ASC")
    private List<RouteRecommendationStop> stops = new ArrayList<>();

   //선택했지만 추천 경로에서 제외된 어트랙션 목록
   @OneToMany(mappedBy = "routeRecommendation", cascade =  CascadeType.ALL, orphanRemoval = true)
    private List<RouteRecommendationExclusion> exclusions = new ArrayList<>();

   //전달받은 사용자가 이 추천의 소유자인지 확인
   public boolean isOwnedBy(Long userId) {
       return this.userId.equals(userId);
   }

   //계산된 방문 ,제외 목록으로 저장할 추천 경로를 생성한다.
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

       //전달받은 목록의 배치 순서랑 상관없이 방문 순번으로 정렬함
       stops.stream()
               .sorted(Comparator.comparing(RouteRecommendationStop::getSequenceNumber))
               .forEach(recommendation::addStop);
       exclusions.forEach(recommendation::addExclusion);

       recommendation.summarize(); // 모든 자식이 연결된 뒤 결과 유형과 합계를 계산
       return recommendation;
   }

   private void addStop(RouteRecommendationStop stop) {
       stops.add(stop); //부모 → 자식 참조를 만듬
       stop.assignRouteRecommendation(this); //자식 → 부모 참조를 만듬 , DB 외래 키를 관리하는 쪽은 이 자식 참조
   }
   private void addExclusion(RouteRecommendationExclusion exclusion) {
       exclusions.add(exclusion);
       exclusion.assignRouteRecommendation(this);
   }
   //방문, 제외 목록을 기준으로 결과 요약을 계산
   private void summarize() {
       RouteRecommendationStop lastStop = stops.get(stops.size() - 1); //정렬된 방문 일정 중 마지막 방문을 선택
       //서비스가 각 선택 항목을 방문 or 제외로 정확히 분류햇다는 전제
       this.totalSelectedAttractions = stops.size() + exclusions.size();
       this.resultType = exclusions.isEmpty() ? RouteResultType.FULL :RouteResultType.PARTIAL;
       //각 방문의 이동 시간을 정수 스트림으로 변환하여 합산
       this.totalMoveMinutes = stops.stream().mapToInt(RouteRecommendationStop::getEstimatedMoveMin).sum();
       //각 방문의 대기 시간을 합산
       this.totalWaitMinutes = stops.stream().mapToInt(RouteRecommendationStop::getEstimatedWaitMin).sum();
       //각 방문의 탑승 시간을 합산
       this.totalRideMinutes = stops.stream().mapToInt(RouteRecommendationStop::getEstimatedRideMin).sum();
       //추천 일정의 종료는 마지막 방문의 탑승 종료 시각
       this.estimatedEndAt =lastStop.getSlotEndAt();
       //전체 소요 시간은 이용 시작부터 마지막 탑승 종료까지 계산
       this.totalDurationMinutes = (int) Duration.between(startAt, estimatedEndAt).toMinutes();
   }
   //소프트 삭제 여부를 반환 -> 삭제 시각의 유무를 확인
    public boolean isDeleted() {
        return deletedAt != null;
    }
    //추천 이력에 삭제 시각을 기록
    public void delete(Instant now) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = now;
    }




}
