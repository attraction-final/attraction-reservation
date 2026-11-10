# 내 예약 목록 N+1 개선

> **KAN-126** · 슬롯·어트랙션 fetch join으로 조회 건수에 비례하던 추가 SELECT를 제거했다.

## 1. 문제와 원인

`ReservationService.findReservationAll()`의 DTO 변환에서 슬롯 시각과 어트랙션 이름을 읽으면서 지연 로딩이 발생했다.

```text
목록 1개 + count 1개 + 슬롯 N개 + 어트랙션 N개 = 2 + 2N
```

목록이 10건에서 50건으로 늘면 SELECT도 22개에서 102개로 증가했다. 응답 결과가 정확한 것과 조회 비용이 일정한 것은 별도로 검증했다.

## 2. 재현 조건

| 항목 | 조건 |
| --- | --- |
| DB·스키마 | Testcontainers MySQL 9.6, Hibernate create-drop |
| 실행 범위 | 실제 Service·Repository·DTO 변환 |
| 데이터 | 본인 RESERVED 예약 50건 + 더 큰 ID의 타인 예약 1건 |
| 연관 데이터 | 예약마다 다른 슬롯·어트랙션, 입장 처리된 티켓 |
| 조회 | 본인 회원, 2026-11-09, 첫 페이지, 크기 10 / 50 |
| 정렬 | status DESC, id DESC |
| 캐시·통계 | 각 조회 전 flush·clear·Statistics.clear, 2차·Query Cache 비활성화 |
| 측정 도구 | Hibernate Statistics, 대상 클래스 단독 실행 |

서로 다른 연관 엔티티를 사용해 1차 캐시가 N+1을 가리지 않게 했다. fixture INSERT는 측정에서 제외했다. 목록에서 사용하지 않는 협력 Service만 mock 처리했다.

## 3. 변경과 결과

`ReservationCustomRepositoryImpl.findReservationAllByCondition()`의 content 쿼리에 두 to-one 관계의 fetch join을 적용했다.

```java
.selectFrom(reservation)
.join(reservation.attractionSlot, attractionSlot).fetchJoin()
.join(attractionSlot.attraction, attraction).fetchJoin()
```

기존 소유자·상태·날짜 조건, 정렬·offset·limit, count 쿼리와 DTO 구조는 유지했다. DTO projection과 컬렉션 fetch join은 적용하지 않았다.

| 페이지 크기 | 개선 전 SELECT | 개선 후 SELECT | 추가 엔티티 fetch 전 → 후 |
| ---: | ---: | ---: | ---: |
| 10 | 22 | 2 | 20 → 0 |
| 50 | 102 | 2 | 100 → 0 |

두 경우 모두 목록·count의 Hibernate queryExecutionCount는 2였다. 이 값만으로 전체 SQL 수를 판단하면 지연 로딩을 놓칠 수 있어, 읽기 구간의 prepareStatementCount를 SELECT 수로 확인했다.

| 확인 시점(KST) | 결과 |
| --- | --- |
| 2026-11-06 17:35:23, 개선 전 | 테스트 1개 실패. SELECT 상한 2개와 페이지 간 동일 SQL 수 검증 3개 실패 |
| 2026-11-06 19:55:47, 개선 후 | 테스트 1개 통과, 실패·오류·건너뜀 0개 |

실패 전에도 응답 필드·ID 순서·타인 예약 제외·전체 건수·페이지 정보 검증은 통과했다.

참고 실행 시간은 10건 450.097→300.041ms, 50건 414.030→39.235ms였다. 단일 실행이고 워밍업을 통제하지 않았으므로 속도 개선율로 사용하지 않는다.

## 4. 검증 범위와 남은 확인

| 구분 | 확인 내용 |
| --- | --- |
| 확인 완료 | 동일 조건의 10건·50건에서 SELECT 2개 유지 |
| 확인 완료 | 현재 fixture의 응답·소유자 제한·정렬·페이지 정보 |
| 이 측정에 미포함 | 다른 상태·날짜 조합, 빈 결과, 다음 페이지의 별도 조건별 통합 검증 |
| 후속 작업 | 관리자 목록, 실행 계획·인덱스 분석, HTTP 부하 테스트 |

Statistics는 SessionFactory 전체 통계이므로 병렬 작업이 섞이지 않게 측정해야 한다. 이번 결과는 운영 p95나 최대 처리량을 보장하지 않는다.

## 5. 재현 방법과 관련 코드

```bash
./gradlew test --tests "*ReservationListQueryIntegrationTest" --rerun-tasks
```

- [측정 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationListQueryIntegrationTest.java)
- [Repository](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java)
- [Service](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java)
- [응답 DTO](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchReservationResponse.java)

수치는 당시 사용자가 실행한 테스트 XML에서 확인했다. 원본 첨부 대신 조건·핵심 지표·검증 한계를 이 문서에 기록하며, 문서 정리 과정에서 테스트를 재실행하지 않았다.
