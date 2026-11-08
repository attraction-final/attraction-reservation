# 내 예약 목록 실행 계획과 복합 인덱스 개선

> **KAN-130** · `(user_id, reservation_status, id)` 인덱스로 두 목록의 Sort를 제거하고 예약 조회량을 500→10건으로 줄였다. 저장 공간·쓰기 비용도 비교했다.

## 1. 문제와 측정 환경

KAN-126에서 N+1을 개선한 뒤 개별 목록·count SQL의 비용을 측정했다. 기존 `(user_id, attraction_slot_id, active_reservation)` UNIQUE 인덱스는 회원 검색에 사용됐지만 상태 필터·정렬에는 추가 처리가 필요했다.

| 항목 | 조건 |
| --- | --- |
| DB·스키마 | Testcontainers MySQL 8.4.11, Flyway + Hibernate validate |
| 격리 수준 | REPEATABLE-READ |
| 데이터 | 회원 200명·예약 100,000건·슬롯 500개·시설 10개 |
| 회원별 이력 | 500건, 네 상태 각각 125건 |
| 기간 | 2026-10-01부터 10일 |
| 조회 | 회원 101, 첫 페이지 10건, 날짜 조건 없음 |
| 조건 | 회원만 / 회원+RESERVED |
| 정렬 | status DESC, id DESC |
| 반복 | SQL별 EXPLAIN ANALYZE 워밍업 1회 후 5회 |

실제 Service·Repository·DTO 변환에서 SQL과 JDBC 바인딩을 수집했다. 2차·Query Cache는 껐고 fixture 커밋 후 ANALYZE TABLE을 수행했다. 조회가 사용하지 않는 협력 Service만 mock 처리했다.

회원별 500건·상태별 25%는 합성 실험 조건이며 운영 분포로 가정하지 않는다. 측정 시간은 warm-cache의 실행 계획 최상위 노드 완료 값(ms)이다.

## 2. 적용한 변경

| 위치 | 변경 |
| --- | --- |
| Reservation의 @Table.indexes | `idx_reservations_user_status_id` 정의 |
| Flyway V4 | 같은 이름·컬럼 순서로 실제 DB 인덱스 생성 |
| 기존 UNIQUE·조회 API | 유지 |

```sql
CREATE INDEX idx_reservations_user_status_id
    ON reservations (user_id, reservation_status, id);
```

Hibernate validate가 인덱스를 생성한 것이 아니라 Flyway V4가 생성했다. 적용 성공 이력과 실제 계획에서 새 인덱스 사용을 확인했다.

## 3. 실행 계획 전후

| 쿼리 | 개선 전 | 개선 후 |
| --- | --- | --- |
| 회원별 목록 | 회원 예약 500건 조회 → 상태·ID Sort → 10건 반환 | 새 인덱스 역방향 조회, 예약 10건 읽기, Sort 없음 |
| 회원+RESERVED 목록 | 500건 조회 → 상태 125건 필터 → ID Sort | 회원·상태 조건의 새 인덱스에서 10건 읽기, Sort 없음 |
| 회원별 count | 기존 인덱스 covering 조회 500건 | 새 인덱스 covering 조회 500건 |
| 회원+RESERVED count | 500건 조회 → 상태 125건 필터·집계 | 회원·상태 covering 조회 125건 |

대표 목록 경로:

```text
개선 전: Index lookup(user_id=101) rows=500 → Sort → Limit 10
개선 후: Index lookup(user_id=101) (reverse) rows=10 → Limit 10
```

슬롯·어트랙션은 양쪽 모두 PK를 각각 10회 조회했다. 상태 조건의 개선 후 계획에 Filter 노드는 남았지만, 인덱스 조회부터 회원·상태를 함께 사용했다. `rows`는 해당 노드의 `loops`와 함께 해석했다.

### 5회 완료 시간

| 쿼리 | 개선 전 5회(ms) | 개선 후 5회(ms) | 중앙값 전 → 후(ms) |
| --- | --- | --- | --- |
| 회원별 목록 | 1.14, 1.23, 1.18, 1.45, 1.46 | 0.158, 0.307, 0.165, 0.161, 0.168 | **1.230 → 0.165** |
| 회원+RESERVED 목록 | 1.25, 1.13, 1.08, 1.13, 1.20 | 0.129, 0.118, 0.132, 0.177, 0.120 | **1.130 → 0.129** |
| 회원별 count | 0.143, 0.105, 0.104, 0.104, 0.103 | 0.0983, 0.0958, 0.0975, 0.0998, 0.102 | **0.104 → 0.0983** |
| 회원+RESERVED count | 0.897, 0.968, 0.912, 0.891, 0.913 | 0.0531, 0.0747, 0.0534, 0.0648, 0.0529 | **0.912 → 0.0534** |

회원별 count는 원래도 covering 경로여서 차이가 작았다. 내부 노드 시간을 합산하거나 위 값을 HTTP 응답 시간으로 표현하지 않는다.

## 4. 인덱스 유지 비용

같은 초기 데이터 10만 건을 가진 복제 테이블 두 개에서 후보 인덱스 유무만 다르게 했다. 기존 인덱스·UNIQUE·Generated Column을 유지했고 CREATE TABLE LIKE가 복제하지 않는 FK는 다시 추가했다.

| 측정 방식 | 조건 |
| --- | --- |
| INSERT | RESERVED 1,000건, 반복별 다른 ID·슬롯 |
| UPDATE | 기존 RESERVED 1,000건 → CANCELLED, 사유·시각·updated_at 반영 |
| 반복 | 워밍업 1회 + 측정 5회, 인덱스 유무 실행 순서 교대 |
| 시간 범위 | JDBC batch와 트랜잭션 시작·커밋 포함 |
| 제외 | fixture·DDL·통계 갱신·검증 조회 |
| 데이터 변화 | 두 테이블 모두 100,000 → 106,000건 |
| 결과 검증 | 처리 행·취소 상태·보조 컬럼·최종 분포 일치, 원본 예약 10만 건 유지 |

### 저장 공간

`mysql.innodb_index_stats`의 size 페이지 수 × 페이지 크기(16,384 bytes)로 추정했다.

| 시점 | 새 인덱스 페이지 | 추정 bytes | 추정 MiB |
| --- | ---: | ---: | ---: |
| 예약 100,000건 | 289 | 4,734,976 | **4.52** |
| 106,000건 및 상태 변경 후 | 287 | 4,702,208 | 4.48 |

통계 기반 추정치이며 정확한 파일 실사용량이 아니다. 행 증가만으로 페이지 크기가 선형 증가한다고 단정하지 않는다.

### 쓰기 시간

단위는 **1,000건 + 커밋의 ms**, 값 목록은 크기순이다.

| 작업·조건 | 5회 측정값(ms) | 중앙값(ms) |
| --- | --- | ---: |
| INSERT, 인덱스 없음 | 44.3610, 48.0690, 50.7905, 51.4474, 54.5113 | 50.7905 |
| INSERT, 인덱스 있음 | 44.9565, 49.7980, 51.7038, 53.1279, 120.0087 | 51.7038 |
| UPDATE, 인덱스 없음 | 163.7959, 171.3746, 176.0352, 184.9094, 196.3340 | 176.0352 |
| UPDATE, 인덱스 있음 | 180.9859, 203.5859, 216.3117, 251.4792, 325.3216 | 216.3117 |

INSERT 중앙값 차이는 +0.9133ms, 상태 UPDATE는 +40.2765ms였다. INSERT 120.0087ms처럼 변동도 있었다. 새 인덱스 유지 비용과 맞는 방향이지만 캐시·페이지 구성·백그라운드 I/O를 배제하지 않았으므로 순수 인덱스 비용으로 확정하지 않는다.

이 측정은 단일 스레드 SQL batch다. 업무 Service·슬롯 인원 변경·HTTP·동시 잠금 경합을 포함하지 않는다.

## 5. 검증 결과와 판단

| 실행 시점(KST) | 범위 | 확인 결과 |
| --- | --- | --- |
| 2026-11-06 23:49:08 | 개선 전 계획 수집 | 테스트 1개 통과 |
| 2026-11-07 00:41:41 | V4 적용 후 계획 수집 | 테스트 1개 통과, V4 적용 성공 |
| 2026-11-07 01:14:07 | 유지 비용 측정 포함 | 테스트 1개 통과 |
| 이후 관련 회귀 실행 | 예약 + Flyway | **41개 Suite, 240개 통과**, 실패·오류·건너뜀 0개 |

회귀 240개에는 측정 테스트 1개와 FlywayMigrationIntegrationTest 4개가 포함된다. 전체 프로젝트 테스트 수로 확대하지 않는다.

현재 실험에서는 조회 이득과 추가 공간·쓰기 비용을 함께 확인하여 후보 인덱스를 유지했다. 운영 읽기·쓰기 비율, 데이터 편중, p95·처리량·부하 상황까지 검증한 것은 아니다. 날짜 필터·깊은 offset·관리자 목록은 별도 범위다.

## 6. 재현과 관련 코드

```bash
./gradlew test --tests "*ReservationListIndexIntegrationTest" --rerun-tasks
./gradlew test --tests "*Reservation*" --tests "*FlywayMigrationIntegrationTest" --rerun-tasks
```

- [측정 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationListIndexIntegrationTest.java)
- [유지 비용 도우미](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationIndexMaintenanceMeasurement.java)
- [Repository](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java)
- [V4 migration](../../src/main/resources/db/migration/V4__add_reservation_user_status_id_index.sql)
- [Flyway 규칙](../database/migration.md)
- [MySQL 실행 계획 해석](https://dev.mysql.com/doc/refman/8.4/en/explain.html)

당시 사용자가 실행한 XML·계획·통계에서 확인한 값을 본문에 남겼다. 원본 로그·XML·해시 첨부는 정리했으며, 문서 정리 중 테스트·DB 쿼리를 실행하지 않았다.
