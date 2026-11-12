# 관리자 예약 목록 N+1 개선

> **KAN-129** · content 쿼리의 슬롯·어트랙션 fetch join으로 SELECT를 일정하게 유지했다. 측정·회귀 테스트 11개 통과.

## 1. 문제와 원인

관리자 목록은 슬롯·어트랙션을 일반 join으로 연결했지만, DTO 변환에서 슬롯 시각과 어트랙션 이름에 접근할 때 추가 SELECT가 발생했다.

```text
목록 1개 + count 1개 + 슬롯 N개 + 어트랙션 N개 = 2 + 2N
```

일반 join으로 검색 조건을 연결하는 것과 연관 엔티티를 함께 로딩하는 것은 별개다. BR-RSV-20의 관리자 필터·검색·페이징을 유지하면서 조회 비용을 개선했다. SELECT 2개는 현재 목록·count 구조의 실험 목표다.

## 2. 재현 조건

| 항목 | 조건 |
| --- | --- |
| DB·스키마 | Testcontainers MySQL 8.4, create-drop, 해당 테스트의 Flyway 비활성화 |
| 실행 범위 | 실제 Service·Repository·DTO 변환 |
| 기본 데이터 | 두 회원의 RESERVED 예약 각 25건, 총 50건 |
| 연관 데이터 | 예약마다 다른 슬롯·어트랙션, 방문일에 입장한 티켓 |
| 조회 | 필터 없음, 첫 페이지, 크기 10 / 50 |
| 정렬 | status DESC, id DESC |
| 캐시·통계 | 조회 전 flush·clear·Statistics.clear, 2차·Query Cache 비활성화 |

사용하지 않는 협력 Service만 mock 처리했다. Statistics는 SessionFactory 단위이므로 클래스를 단독 실행했다. 이 테스트는 migration 검증을 대체하지 않는다.

## 3. 변경과 결과

`findAdminReservationByCondition()`의 content 쿼리에 각 관계의 fetch join을 적용했다.

```java
.selectFrom(reservation)
.join(reservation.attractionSlot, attractionSlot).fetchJoin()
.join(attractionSlot.attraction, attraction).fetchJoin()
```

필터·정렬·페이징·응답 구조와 count 쿼리의 일반 join은 유지했다.

| 페이지 크기 | 개선 전 SELECT | 개선 후 SELECT | 추가 엔티티 fetch 전 → 후 |
| ---: | ---: | ---: | ---: |
| 10 | 22 | 2 | 20 → 0 |
| 50 | 102 | 2 | 100 → 0 |

| 확인 시점(KST) | 결과 |
| --- | --- |
| 2026-11-06 21:50:19, 개선 전 | 테스트 1개 실패. SELECT 수 검증 3개 실패, 응답 검증은 통과 |
| 2026-11-06 22:23:31, 개선 직후 | 측정 테스트 1개 통과 |
| 2026-11-06 22:32:10, 회귀 보강 후 | 11개 통과, 실패·오류·건너뜀 0개 |

단일 측정 시간은 개선 직후 10건 248.149ms, 50건 35.375ms였고, 회귀 보강 후에는 6.452ms와 14.581ms였다. 초기화·실행 순서가 달라 시간 차이를 속도 개선율이나 추가 최적화 효과로 해석하지 않는다.

## 4. 회귀 검증 11개

| 항목 | 케이스 수 | 핵심 검증 |
| --- | ---: | --- |
| 기본 측정 | 1 | 10건·50건 SELECT 수, 응답·정렬·페이지 정보 |
| 회원 필터 | 1 | 지정 회원 25건과 전체 건수 |
| 어트랙션 필터 | 1 | 같은 시설의 여러 슬롯·회원 예약 |
| 상태 필터 | 3 | CANCELLED·COMPLETED·NO_SHOW 조회, RESERVED에서 제외 |
| 날짜 필터 | 1 | 지정 날짜 포함, 전날·다음 날 제외 |
| 복합 조건 | 1 | 회원·시설·상태·날짜 동시 적용 |
| 빈 결과 | 1 | 내용·건수·페이지 0, 다음 페이지 없음 |
| 페이지 | 2 | 두 번째·마지막 부분 페이지의 ID 순서·건수 |
| **합계** | **11** | 조건별 조회도 SELECT 2개 이하 |

자정 경계값과 모든 정렬·필터 조합을 전수 검증한 것은 아니다. 이 기록의 11개는 관련 예약 테스트 전체나 PR CI의 통과 수가 아니다.

## 5. 재현 방법과 범위

```bash
./gradlew test --tests "*AdminReservationListQueryIntegrationTest" --rerun-tasks
```

- [측정·회귀 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationListQueryIntegrationTest.java)
- [Repository](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java)
- [Service](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java)
- [응답 DTO](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationResponse.java)

관리자 상세·인덱스·count 생략·Cursor Pagination·부하 테스트는 별도 작업이다. 수치는 당시 실제 테스트 결과에서 확인했으며 원본 첨부 대신 핵심 결과를 본문에 기록한다. 문서 정리 중 테스트를 재실행하지 않았다.
