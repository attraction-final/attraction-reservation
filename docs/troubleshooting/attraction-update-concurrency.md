# 어트랙션 일반 수정의 운영 상태·삭제 결과 덮어쓰기 트러블슈팅

> **KAN-102 · 일반 수정과 운영 중단·삭제의 동시성 보호**
>
> 실제 MySQL에서 삭제가 먼저 commit되어도, 이전에 조회한 엔티티로 일반 수정을 완료하면 삭제 결과가 되돌아가는 문제를 재현했다.
> 일반 수정에도 기존 비관적 잠금 조회를 적용하고, 삭제·운영 중단과의 두 선행 순서를 검증했다.
> 관련 테스트 실행 후 생성된 XML에서 51개 Test Suite, 353개 테스트의 통과를 확인했다.

## 1. 문제와 영향

관리자가 어트랙션 설명을 수정하는 동안 다른 관리자가 같은 어트랙션을 삭제할 수 있다.

수정 전에는 일반 수정이 이전에 읽은 엔티티로 UPDATE를 실행하면서, 설명 외에 운영 상태와 삭제 시각도 이전 값으로 덮어쓸 수 있었다.

실제 재현 결과는 다음과 같다.

```text
삭제 commit 직후
operationStatus = DELETED
deletedAt       = 삭제 시각

앞서 조회한 일반 수정이 commit된 후
operationStatus = OPERATING
deletedAt       = null
```

설명만 수정했는데 삭제된 어트랙션이 다시 활성 조회 대상이 되는 데이터 정합성 문제였다.

운영 중단 결과를 이전 OPERATING 상태로 덮어쓰는 경우도 같은 구조에서 발생할 수 있다고 판단했다. 다만 수정 전 실패를 실제로 재현한 것은 **삭제 경합**이며, 운영 중단 경합은 수정 후 테스트로 검증했다.

## 2. 문서 기준과 검토 범위

읽기 전용 원본 `sources/비즈니스 규칙.md`와 `sources/사용자 시나리오.md`에서 관련 항목을 확인했다. 원본 문서는 변경하지 않았다.

- **BR-ATR-05:** 운영중이 아닌 상태로 변경할 때 이용 예정 예약을 확인한다.
- **BR-ATR-06:** 이용 예정 예약이 있는 어트랙션의 삭제를 제한하고, 삭제는 소프트 삭제로 처리한다.
- **BR-COM-07:** 소프트 삭제된 어트랙션은 조회 시 기본으로 제외한다.
- **사용자 시나리오 4-4~4-6:** 일반 정보 수정, 삭제, 운영 상태 변경 및 변경 결과 반영.

문서가 특정 잠금 방식을 지정한 것은 아니다. 완료된 상태 변경·삭제 결과를 다른 수정이 되돌리지 않도록 보호하는 구현 보완으로 비관적 잠금을 선택했다.

운영 중단 상태의 일반 정보 수정은 현재 구현에서 허용한다. 이번 작업에서 별도 금지 정책을 추가하지 않았으며, 운영 중단 선행 시 두 요청이 성공하면서 각 변경이 유지되는지 검증했다.

## 3. 원인: 변경 경로마다 달랐던 보호 방식

수정 전 조회 방식은 다음과 같았다.

- 일반 수정: `findActiveAttraction()` → `findByIdAndDeletedAtIsNull()` 일반 조회
- 운영 상태 변경: `findActiveAttractionForUpdate()` → `findActiveByIdForUpdate()` 잠금 조회
- 삭제: 운영 상태 변경과 같은 잠금 조회
- `Attraction`에는 `@Version`, `@DynamicUpdate`가 없었다.

일반 수정의 `@Transactional`은 요청 내부 변경을 하나의 트랜잭션으로 묶지만, 다른 요청이 같은 행을 변경하는 동안 오래된 상태를 읽는 것까지 방지하지 않는다.

Hibernate의 기본 엔티티 UPDATE는 변경하지 않은 updateable 컬럼도 포함할 수 있다. 이번 매핑에서는 설명 변경으로 UPDATE가 발생할 때 영속성 컨텍스트에 남은 이전 운영 상태와 삭제 시각도 함께 기록됐다.

```text
일반 수정 A                          삭제 B
----------------------------------------------------------
OPERATING, deletedAt=null 조회
                                     잠금 조회
                                     DELETED로 변경
                                     deletedAt 기록
                                     commit
이전 엔티티의 설명 변경
오래된 상태·삭제 시각까지 UPDATE
commit
→ 삭제 결과 유실
```

일반 수정에 있던 응답 전 `flush()`는 Auditing 시각 반영을 위한 처리였다. flush 시점을 앞당겨도 이미 읽은 오래된 상태가 최신 상태로 바뀌는 것은 아니므로, 이번 경합을 방지하지 못했다.

## 4. 수정 전 재현

추가한 테스트 파일:

[AttractionUpdateConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/attraction/service/AttractionUpdateConcurrencyIntegrationTest.java)

### 환경

- `@DataJpaTest`, `@ActiveProfiles("test")`
- Testcontainers MySQL `mysql:9.6`
- `ddl-auto=create-drop`, Hikari 최대 커넥션 4개
- 테스트 메서드의 기본 트랜잭션은 `NOT_SUPPORTED`
- fixture 준비와 최종 상태 조회는 `TransactionTemplate`로 별도 수행
- 실제 `AttractionService`와 실제 예약 수 조회 구현 사용
- Redis 캐시 삭제만 `@MockitoBean`으로 제외

### 최초 재현 테스트의 실행 순서

1. OPERATING 상태이며 삭제되지 않은 어트랙션을 저장하고 commit한다.
2. 일반 수정의 Repository 호출을 Spy로 제어한다. 이 안에서 실제 JPQL로 엔티티를 조회하고, 조회 직후 대기한다.
3. 다른 스레드에서 실제 삭제 Service를 호출하고 commit까지 기다린다.
4. 별도 트랜잭션에서 DELETED 상태와 삭제 시각이 저장됐는지 확인한다.
5. 일반 수정을 재개하여 설명을 실제로 변경하고 commit한다.
6. 별도 트랜잭션에서 최종 상태와 삭제 시각을 확인한다.

설명을 기존 값과 다르게 변경하여 실제 dirty checking과 UPDATE가 발생하도록 했다. 변경 없는 요청으로 검사하면 UPDATE가 발생하지 않아 문제를 놓칠 수 있다.

### 실제 실패 결과

- 최초 재현 테스트의 XML 시작 시각: **2026-11-03 15:25:57 KST**
- 테스트 1개 중 1개 실패, 실행 오류 0개
- `operationStatus`: 기대 `DELETED`, 실제 `OPERATING`
- `deletedAt`: 기대 삭제 시각 유지, 실제 `null`
- 기대했던 삭제 시각: `2026-11-03T06:26:33.468390Z`

환경 초기화 실패가 아니라 최종 DB 상태 비교에서 실패하여 삭제 결과 덮어쓰기를 확인했다.

최초 재현 테스트는 이후 잠금 적용 후의 검증 테스트로 교체했다. 최초 실패 내용은 당시 확인한 리포트를 바탕으로 이 문서에 기록했으며, 현재 XML에는 마지막 실행 결과가 남는다.

## 5. 해결: 일반 수정도 같은 행 잠금에 참여

[AttractionService.java](../../src/main/java/com/example/attractionreservation/domain/attraction/service/AttractionService.java)의 `updateAttraction()`에서 첫 조회만 변경했다.

```java
// 수정 전
Attraction attraction = findActiveAttraction(attractionId);

// 수정 후
Attraction attraction = findActiveAttractionForUpdate(attractionId);
```

기존 [AttractionRepository.java](../../src/main/java/com/example/attractionreservation/domain/attraction/repository/AttractionRepository.java)의 잠금 조회를 재사용했다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Attraction a WHERE a.id = :attractionId AND a.deletedAt IS NULL")
Optional<Attraction> findActiveByIdForUpdate(@Param("attractionId") Long attractionId);
```

일반 수정, 운영 상태 변경, 삭제가 같은 어트랙션 행의 배타적 잠금을 사용한다. 기존 `@Transactional` 안에서 조회하므로 잠금은 트랜잭션 종료까지 유지된다.

- 일반 수정 선행: 후행 상태 변경·삭제가 기다린 뒤 수정 결과를 읽고 처리한다.
- 삭제 선행: 일반 수정이 기다린 뒤 활성 엔티티를 찾지 못해 `ATTRACTION_NOT_FOUND`로 거부된다.
- 운영 중단 선행: 일반 수정이 기다린 뒤 SUSPENDED 상태를 읽고 설명을 수정한다.

엔티티에 `@Version`이나 `@DynamicUpdate`를 추가하지 않았고, 일반 상세 조회는 기존 조회 방식을 유지했다.

## 6. 수정 후 검증 방식

기존 재현 테스트는 일반 조회 직후의 대기를 전제로 했다. 잠금 적용 후에는 삭제가 먼저 완료될 수 없으므로 같은 실행 순서를 그대로 사용하지 않았다.

최종 테스트에서는 Repository Spy를 제거하고, 실제 Service 호출을 외부 `TransactionTemplate`로 감쌌다.

1. 선행 Service가 상태를 변경하고 flush한 뒤 외부 트랜잭션의 commit 직전에 대기한다.
2. 다른 스레드에서 후행 Service를 호출한다.
3. 후행 요청이 500ms 동안 완료되지 않는지 확인한다.
4. 선행 트랜잭션의 commit을 허용한다.
5. 두 요청의 결과와 별도 트랜잭션에서 읽은 최종 DB 상태를 검증한다.

`CountDownLatch`로 요청 시작과 commit 허용 순서를 제어한다. 500ms 확인은 요청이 완료되지 않았다는 검증이며, MySQL 잠금 대기 테이블을 직접 조회하여 대기를 증명한 것은 아니다.

### 검증한 4가지 시나리오

**① 일반 수정 → 삭제**

- 삭제 요청이 선행 수정의 commit을 기다린다.
- 두 요청 모두 성공한다.
- 최종 상태는 DELETED이며 삭제 시각이 존재한다.
- 일반 수정으로 변경한 설명이 유지된다.

**② 삭제 → 일반 수정**

- 일반 수정 요청이 선행 삭제의 commit을 기다린다.
- 삭제는 성공하고 일반 수정은 `ATTRACTION_NOT_FOUND`로 거부된다.
- 최종 DELETED 상태와 삭제 시각이 유지된다.
- 거부된 일반 수정의 설명은 반영되지 않는다.

**③ 일반 수정 → 운영 중단**

- 운영 중단 요청이 선행 수정의 commit을 기다린다.
- 두 요청 모두 성공한다.
- 최종 상태는 SUSPENDED이며 수정한 설명이 유지된다.
- 삭제 시각은 null이다.

**④ 운영 중단 → 일반 수정**

- 일반 수정 요청이 선행 운영 중단의 commit을 기다린다.
- 두 요청 모두 성공한다.
- 일반 수정 응답과 최종 DB의 상태가 SUSPENDED다.
- 수정한 설명이 반영되고 삭제 시각은 null이다.

## 7. Unit Test와 회귀 검증

[AttractionServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/attraction/service/AttractionServiceTest.java)의 일반 수정 테스트 4개에서 조회 mock을 잠금 조회로 변경했다.

- 부분 수정
- 이름 중복 거부
- 같은 이름 요청의 중복 검사 생략
- 존재하지 않는 어트랙션 수정 거부

부분 수정 테스트에는 잠금 조회 호출 및 일반 조회 미호출 검증도 추가했다. 상세 조회 테스트의 일반 조회 mock은 유지했다.

사용자가 실행한 명령어:

```bash
# 수정·삭제 경합 및 기존 Unit Test
./gradlew test --tests "*AttractionUpdateConcurrencyIntegrationTest" --tests "*AttractionServiceTest" --rerun-tasks

# 운영 중단 경합 추가 후 동시성 테스트 4개
./gradlew test --tests "*AttractionUpdateConcurrencyIntegrationTest" --rerun-tasks

# 관련 기능 회귀 검증
./gradlew test --tests "*Attraction*" --tests "*Reservation*" --rerun-tasks
```

마지막 실행 후 `build/test-results/test`의 XML 51개에서 확인한 결과:

```text
Test Suite: 51
Tests:      353
Failures:   0
Errors:     0
Skipped:    0

AttractionUpdateConcurrencyIntegrationTest: 4개 모두 통과
```

동시성 테스트 XML의 시작 시각은 **2026-11-03 16:34:21 KST**다. 테스트는 사용자가 실행했고, 문서 작성 시 생성된 리포트를 읽어 확인했다. 전체 프로젝트 테스트의 통과를 의미하지는 않는다.

## 8. 검증 범위와 한계

- 실제 MySQL에서 Service·Repository·트랜잭션 경계를 검증했다.
- HTTP Controller와 Spring Security 권한 검증은 포함하지 않았다.
- Redis 캐시 삭제는 mock이므로 실제 캐시 무효화와 Redis 장애 처리는 검증하지 않았다.
- 예약이 없는 fixture를 사용했으므로, 이 4개 테스트 자체가 이용 예정 예약이 있는 상태의 변경 제한을 검증하는 것은 아니다.
- 이번 검증은 설명 수정과 삭제·SUSPENDED 변경의 조합이다. 모든 운영 상태 조합이나 모든 관리자 변경의 동시성을 검증한 것은 아니다.
- 여러 행을 잠그는 다른 업무 경로 전체의 잠금 순서, deadlock, timeout, 부하에 따른 처리량은 별도 검토 대상이다.
- 일반 수정끼리 오래된 화면의 절대값을 제출하는 충돌을 감지하는 버전 정책은 추가하지 않았다.
- 탑승 소요 시간 변경이 기존 슬롯에 미치는 정책은 이번 작업 범위에 포함하지 않았다.

## 9. 정리

엔티티 메서드가 설명만 변경하더라도 실제 UPDATE가 설명 컬럼만 변경한다고 가정할 수 없다. 상태 변경과 삭제가 보호되어 있어도, 같은 행을 변경하는 일반 수정이 보호에 참여하지 않으면 이전 값이 다시 기록될 수 있다.

이번 작업에서는 기존 잠금 조회를 일반 수정에도 적용하여, 같은 어트랙션을 변경하는 세 경로가 순서대로 최신 상태를 확인하도록 보완했다.
