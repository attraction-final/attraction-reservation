package com.example.attractionreservation;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateSelector;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.HaversineStartLegEstimator;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.RouteSearcher;
import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;

// Flyway 마이그레이션이 넣은 배포용 실측 데이터로 추천해 보고,
// 추천이 어트랙션 간 실측 이동 시간(네이버 지도 도보 길찾기, 방향별 값)을 그대로 쓰는지 검증한다.
// - V11: 배포용 어트랙션 10개 (썬더폴스·렛츠 트위스트는 MAINTENANCE)
// - V13(KAN-161): 좌표 10건 + 방향별 구간 90건 (kan-98 좌표 보정 포함)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JpaConfig.class,
        QuerydslConfig.class,
        RouteRecommendationService.class,
        TicketService.class,
        CandidateSelector.class,
        RouteSearcher.class,
        HaversineStartLegEstimator.class,
        RouteRecommendationProperties.class,
        RouteRecommendationMeasuredDataIntegrationTest.MySqlConfig.class
})
class RouteRecommendationMeasuredDataIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 12, 0);

    // 콜럼버스 대탐험 근처의 사용자 위치 (두 어트랙션 모두 서비스 지역 2,000m 안)
    private static final double USER_LATITUDE = 37.2942;
    private static final double USER_LONGITUDE = 127.1997;

    @Autowired
    private RouteRecommendationService routeRecommendationService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private TransactionTemplate transactionTemplate;

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        // 운영 RDS와 같은 버전
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
        }
    }

    @BeforeEach
    void setUp() {
        when(clock.getZone()).thenReturn(KstTime.ZONE);
        when(clock.instant()).thenReturn(NOW.atZone(KstTime.ZONE).toInstant());
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("마이그레이션이 넣은 방향별 구간 90건은 모두 실측(추정 아님)·활성 상태이고 좌표는 10건이다")
    void measuredData_Migrated_AllPathsMeasuredAndActive() {
        // when
        Integer paths = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attraction_paths", Integer.class);
        Integer estimated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attraction_paths WHERE is_estimated = b'1'", Integer.class);
        Integer inactive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attraction_paths WHERE is_active = b'0'", Integer.class);
        Integer locations = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attraction_locations", Integer.class);

        // then
        assertSoftly(softly -> {
            softly.assertThat(paths).isEqualTo(90);
            softly.assertThat(estimated).isZero();
            softly.assertThat(inactive).isZero();
            softly.assertThat(locations).isEqualTo(10);
            // 같은 두 어트랙션이라도 방향마다 실측 시간이 다르다 (역방향 값으로 대신하면 안 되는 이유)
            softly.assertThat(moveMinutes("범퍼카", "콜럼버스 대탐험")).isEqualTo(12);
            softly.assertThat(moveMinutes("콜럼버스 대탐험", "범퍼카")).isEqualTo(9);
        });
    }

    @Test
    @DisplayName("실측 데이터로 추천하면 방향별 실측 이동 시간으로 순서를 고르고, 점검 중인 어트랙션은 운영 중지로 제외한다")
    void createRouteRecommendation_MeasuredPaths_UsesDirectionalMinutes() {
        // given
        Long bumperCar = attractionId("범퍼카");             // 탑승 5분
        Long columbus = attractionId("콜럼버스 대탐험");      // 탑승 3분
        Long thunderFalls = attractionId("썬더폴스");         // MAINTENANCE
        Long letsTwist = attractionId("렛츠 트위스트");       // MAINTENANCE

        // 콜럼버스 13:00(~13:03) → 실측 9분 → 범퍼카 13:12 에 정확히 도착하는 경로만 가장 짧다.
        // 역방향 값(범퍼카 → 콜럼버스 12분)을 썼다면 13:15 도착이라 13:12 슬롯을 놓친다.
        createSlot(columbus, LocalTime.of(13, 0));
        createSlot(columbus, LocalTime.of(13, 20));
        createSlot(bumperCar, LocalTime.of(13, 0));
        createSlot(bumperCar, LocalTime.of(13, 12));
        Member member = createMember();

        CreateRouteRecommendationRequest request = new CreateRouteRecommendationRequest(
                member.ticketId(),
                List.of(columbus, bumperCar, thunderFalls, letsTwist),
                USER_LATITUDE, USER_LONGITUDE, 10.0,
                null, null, null, null, null);

        // when
        SearchRouteRecommendationResponse result =
                routeRecommendationService.createRouteRecommendation(member.userId(), request).recommendation();

        // then
        List<SearchRouteRecommendationResponse.Visit> visits = result.visits();
        assertThat(visits).extracting(SearchRouteRecommendationResponse.Visit::attractionId)
                .containsExactly(columbus, bumperCar);
        SearchRouteRecommendationResponse.Visit first = visits.get(0);
        SearchRouteRecommendationResponse.Visit second = visits.get(1);
        assertSoftly(softly -> {
            softly.assertThat(result.resultType()).isEqualTo(RouteResultType.PARTIAL);
            // 첫 구간(현재 위치 → 첫 어트랙션)은 실측 구간이 없어 항상 추정값이다.
            softly.assertThat(first.moveEstimated()).isTrue();
            softly.assertThat(first.startsAt()).isEqualTo(NOW.withHour(13));
            // 어트랙션 간 구간은 실측값(콜럼버스 → 범퍼카 9분)을 그대로 쓴다.
            softly.assertThat(second.estimatedMoveMinutes()).isEqualTo(9);
            softly.assertThat(second.moveEstimated()).isFalse();
            softly.assertThat(second.estimatedArrivalAt()).isEqualTo(NOW.withHour(13).withMinute(12));
            softly.assertThat(second.estimatedWaitMinutes()).isZero();
            softly.assertThat(second.startsAt()).isEqualTo(NOW.withHour(13).withMinute(12));
            softly.assertThat(result.excludedAttractions())
                    .extracting(SearchRouteRecommendationResponse.ExcludedAttraction::attractionId,
                            SearchRouteRecommendationResponse.ExcludedAttraction::reasonCode)
                    .containsExactly(
                            tuple(thunderFalls, "NOT_OPERATING"),
                            tuple(letsTwist, "NOT_OPERATING"));
        });
    }

    private Long attractionId(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM attractions WHERE name = ? AND deleted_at IS NULL", Long.class, name);
    }

    private Integer moveMinutes(String from, String to) {
        return jdbcTemplate.queryForObject("""
                SELECT p.move_minutes FROM attraction_paths p
                JOIN attractions f ON f.id = p.from_attraction_id
                JOIN attractions t ON t.id = p.to_attraction_id
                WHERE f.name = ? AND t.name = ?
                """, Integer.class, from, to);
    }

    private void createSlot(Long attractionId, LocalTime startTime) {
        transactionTemplate.executeWithoutResult(status -> {
            AttractionSlot slot = AttractionSlot.create(entityManager.find(Attraction.class, attractionId),
                    NOW.toLocalDate().atTime(startTime), 10);
            entityManager.persist(slot);
        });
    }

    // 오늘 10:00에 입장해 이용 중(IN_USE)인 종일권을 가진 회원
    private Member createMember() {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            TicketProduct product = new TicketProduct("종일 이용권", 50000, "실측 데이터 추천 테스트 상품",
                    TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
            entityManager.persist(product);
            User user = User.builder()
                    .email("md-" + UUID.randomUUID().toString().replace("-", "") + "@test.com")
                    .password("test-password")
                    .nickname("실측추천회원")
                    .build();
            entityManager.persist(user);
            Order order = Order.create(user, 50000, NOW.toLocalDate());
            entityManager.persist(order);
            OrderItem item = OrderItem.create(order, product, 1);
            entityManager.persist(item);
            Ticket ticket = new Ticket(NOW.toLocalDate(), item);
            ticket.entryTicket(NOW.withHour(10).atZone(KstTime.ZONE).toInstant());
            entityManager.persist(ticket);
            return new Member(user.getId(), ticket.getId());
        }));
    }

    private record Member(Long userId, Long ticketId) {
    }
}