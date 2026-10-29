# 예약 동시성 트러블슈팅 기록

> **KAN-87 · 진행 중**
>
> 같은 회원·같은 슬롯의 동시 예약을 실제 MySQL에서 재현했다. 요청 10개가 모두 성공하여 활성 예약 10건이 생성됐다. 해결 방식 적용과 개선 후 검증은 아직 진행하지 않았다.

## 1. 진행 현황

| 단계 | 상태 | 확인 내용 |
| --- | --- | --- |
| 동일 슬롯 중복 예약 재현 | 완료 | 동일 회원의 요청 10개가 모두 성공 |
| 서로 다른 슬롯의 시간 충돌 재현 | 미진행 | 별도 동시성 테스트 필요 |
| 해결 방식 비교 및 선택 | 미진행 | 동일 슬롯 중복과 다른 슬롯 시간 충돌을 함께 고려 |
| 해결 적용 및 회귀 검증 | 미진행 | 기존 재현 테스트로 개선 전후 비교 |
| 취소 후 재예약 검증 | 미진행 | 중복 방지 장치가 정상 재예약을 막지 않는지 확인 |

이 문서에는 **테스트로 확인한 사실**, **코드에 근거한 분석**, **앞으로 검증할 사항**을 구분해 누적한다. 아직 수행하지 않은 테스트는 완료로 기록하지 않는다.

## 2. 문제와 비즈니스 규칙

예약 생성은 다음 순서로 진행된다.

```text
회원·티켓·슬롯 검증
    → 같은 회원의 동일 슬롯 활성 예약 확인
    → 같은 회원의 다른 활성 예약과 시간 충돌 확인
    → 슬롯 정원 확보
    → 예약 저장 및 transaction commit
```

중복·충돌 조회와 저장 사이에 다른 요청이 같은 검사를 통과하면, 순차 요청에서는 지켜지던 규칙이 동시 요청에서는 깨질 수 있다.

### 문서에 명시된 요구사항

출처: 원본 참고 자료 `sources/비즈니스 규칙.md`의 예약 규칙·취소 규칙·동시성 규칙. 원본 문서는 변경하지 않았다.

| 규칙 | 요구사항 | 이번 작업과의 관계 |
| --- | --- | --- |
| BR-RSV-03 | 같은 회원의 같은 슬롯 중복 예약 금지, 활성 예약 기준 유니크 제약 | 동일 슬롯 중복 방지 |
| BR-RSV-04 | 같은 회원의 활성 예약끼리 시간 중복 금지 | 다른 어트랙션의 슬롯까지 포함 |
| BR-RSV-13 | 취소된 예약은 복구하지 않고 새 예약 생성 | 취소 이력이 정상 재예약을 막으면 안 됨 |
| BR-RSV-30 | 동시 요청에도 정원 초과 방지 | 회원 중복 방지와 별도로 유지 |
| BR-RSV-31 | 동일 회원·동일 슬롯 동시 요청은 DB 유니크 제약으로 1건만 성공 | DB 제약 설계 필요 |
| BR-RSV-32 | 잔여 정원 = 정원 − 예약 완료 건수 | 실패 요청의 rollback과 인원 정합성 확인 |

현재 중복·충돌 조회는 `RESERVED` 상태를 대상으로 한다.

### 관련 코드

경로와 줄 번호는 최초 기록 시점 기준이며, 이후 수정 시 달라질 수 있다.

| 파일 | 메서드 및 위치 | 역할 |
| --- | --- | --- |
| [ReservationService.java](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java) | `createReservation()`, 110–131줄 | 중복·충돌 검사 후 정원 확보 및 저장 |
| [ReservationCustomRepositoryImpl.java](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java) | `existsReservedByUser_IdAndSlot_Id()`, `existsTimeConflictReservation()` | 실제 DB에서 중복·시간 충돌 조회 |
| [AttractionSlotService.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/service/AttractionSlotService.java) | `tryReservationCapacity()` | 조건부 UPDATE 후 엔티티 refresh |
| [Reservation.java](../../src/main/java/com/example/attractionreservation/domain/reservation/entity/Reservation.java) | 테이블 매핑 | 활성 예약 중복을 막는 DB 제약은 엔티티에 선언되지 않음 |

실제 운영 DB에 별도로 적용된 제약은 확인하지 않았다. 이번 재현은 엔티티 매핑으로 생성된 Testcontainers DB에서 수행됐다.

## 3. 재현 ① 동일 회원·동일 슬롯 동시 예약

### 3.1 목적

정원이 충분한 상황에서 같은 회원이 같은 슬롯에 동시에 10번 요청해도 **예약은 1건만 생성되어야 한다**는 규칙을 검증한다.

정원은 요청 수보다 많은 20명으로 설정했다. 정원 부족에 따른 실패와 회원 중복 방지에 따른 실패를 구분하기 위해서다.

### 3.2 환경과 데이터

| 항목 | 설정 및 확인 내용 |
| --- | --- |
| 기록일 | 2026-10-29 |
| 브랜치 | `fix/KAN-87-reservation-concurrency` |
| 기록 시점 HEAD | `10759c4408fb46ec78898c97f01d2955e1cba53b` |
| 테스트 코드 상태 | 기록 시점에는 새 테스트 파일이 아직 untracked 상태. HEAD만으로 재현 테스트까지 복원할 수 없음 |
| DB | Testcontainers MySQL, 이미지 태그 `mysql:9.6` |
| 데이터 접근 | 실제 JPA·Querydsl Repository 및 예약 Service 사용 |
| Connection pool | 최대 20개 |
| 회원·티켓 | 동일한 ACTIVE 회원과 본인 소유 IN_USE 종일권 |
| 기준 시각 | 2026-10-29 14:00 KST로 고정 |
| 슬롯 | 당일 16:00–16:30, 운영 중인 어트랙션, OPEN 상태 |
| 정원·초기 예약 인원 | 정원 20명, reservedCount 0명 |
| 요청 | 동일 회원·티켓·슬롯으로 10개 |
| DB isolation level | 테스트에서 별도 지정하지 않음. 실행 DB의 실제 session 값은 아직 수집하지 않음 |

`mysql:9.6`은 설정한 이미지 태그다. 정확한 이미지 digest와 DB patch version은 이번 기록에서 수집하지 않았다.

### 3.3 테스트 구성

테스트 파일: [ReservationConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationConcurrencyIntegrationTest.java)

테스트 메서드:

```java
createReservation_SameUserAndSlotConcurrently_OnlyOneSucceeds()
```

1. `TransactionTemplate`으로 준비 데이터를 저장하고 commit한다. 작업 스레드가 준비 데이터를 조회할 수 있도록 하기 위해서다.
2. 테스트 자체의 transaction은 `Propagation.NOT_SUPPORTED`로 비활성화한다.
3. 스레드 10개를 준비하고 `CountDownLatch`로 시작 시점을 맞춘다.
4. 각 스레드에서 실제 `ReservationService.createReservation()`을 호출한다. Service의 transaction은 요청별로 독립적으로 실행된다.
5. 현재 시각을 고정하는 static mock은 스레드별로 적용한다. 중복 조회·정원 UPDATE·예약 INSERT는 mock으로 대체하지 않는다.
6. 모든 요청이 종료된 후 별도 transaction으로 활성 예약 수와 슬롯 예약 인원을 조회한다.
7. 테스트가 실패하더라도 해당 테스트가 생성한 데이터를 정리한다.

`PasswordEncoder`와 `RefreshTokenRepository`는 UserService 생성에 필요한 의존성으로 mock을 사용한다. 예약 생성 경로에서 해당 동작을 검증하는 테스트는 아니다.

### 3.4 실행 방법과 로그 확인

Docker를 실행한 뒤 프로젝트 루트의 Git Bash에서 실행한다.

```bash
./gradlew test --tests "*ReservationConcurrencyIntegrationTest"
```

이미 실행된 결과가 캐시되어 실제 재실행이 필요하다면 다음 명령을 사용한다.

```bash
./gradlew test --tests "*ReservationConcurrencyIntegrationTest" --rerun-tasks
```

실행 후 아래 HTML 파일을 브라우저로 열어 테스트 실패 내용과 Standard output을 확인한다.

```text
build/reports/tests/test/index.html
```

핵심 로그만 검색하려면 다음 명령을 사용한다.

```bash
grep -n "동시 예약 결과" build/test-results/test/*ReservationConcurrency*.xml
```

### 3.5 기대 결과와 실제 결과

| 검증 항목 | 기대 | 실제 | 판정 |
| --- | ---: | ---: | --- |
| 성공한 요청 수 | 1 | 10 | 중복 요청 차단 실패 |
| 해당 회원·슬롯의 RESERVED 예약 수 | 1 | 10 | 활성 예약 중복 생성 |
| 슬롯 reservedCount | 1 | 10 | 동일 회원이 여러 자리 확보 |
| 실패한 요청 수 | 9 | 0 | 모든 요청 성공 |
| reservedCount와 실제 활성 예약 수 | 일치 | 10 = 10 | 이번 실행에서는 일치 |

실제 출력:

```text
동시 예약 결과: 요청=10, 성공=10, 활성 예약=10, reservedCount=10, 실패=[]
```

JUnit 결과:

```text
tests=1, skipped=0, failures=1, errors=0
```

기대값과 실제값의 차이로 실패한 assertion은 다음 세 가지다.

```text
[성공한 요청 수]              expected: 1L / actual: 10L
[DB에 저장된 활성 예약 수]    expected: 1L / actual: 10L
[슬롯 예약 인원]              expected: 1  / actual: 10
```

**판정: 동일 회원·동일 슬롯의 중복 예약 문제를 실제 MySQL에서 재현했다.** 테스트 실행 자체가 중단된 것이 아니라, 올바른 동작을 기대하는 검증이 실패했다.

확인한 최신 XML 보고서의 시작 시각은 `2026-10-29T06:42:26.325Z`이며, KST로는 `2026-10-29 15:42:26.325`다. 사용자가 실행한 로컬 결과를 읽어 기록했으며, 문서 작성 과정에서 테스트를 다시 실행하지 않았다.

### 3.6 원인 분석

#### 코드로 확인한 구조

중복·충돌 확인은 SELECT이며, 검사 이후 정원 확보 UPDATE와 예약 INSERT가 실행된다. 여러 요청이 저장 전 검사를 통과할 여지가 있다.

아래는 이 구조에서 가능한 실행 순서를 단순화한 예시다. 각 요청의 상세 SQL·commit 순서를 추적해 복원한 결과는 아니다.

| 순서 | 요청 A | 요청 B |
| --- | --- | --- |
| 1 | 중복·시간 충돌 조회: 없음 | |
| 2 | | 중복·시간 충돌 조회: 없음 |
| 3 | 정원 확보 | |
| 4 | 예약 저장 및 commit | |
| 5 | | 정원 확보 |
| 6 | | 예약 저장 및 commit |

슬롯 정원 UPDATE가 DB에서 순서대로 처리되더라도, **이미 통과한 회원 중복·충돌 검사가 자동으로 다시 실행되지는 않는다.**

`@Transactional`은 한 요청의 정원 확보와 예약 저장을 함께 commit 또는 rollback하도록 묶는다. 현재 설정만으로 동일 회원의 여러 요청 전체가 순차 실행되지는 않는다.

#### 이번 결과가 입증하는 것과 한계

- 입증: 같은 회원·같은 슬롯의 요청 10개가 모두 성공하고, commit된 활성 예약 10건이 조회됐다.
- 입증: 이번 실행에서 reservedCount는 실제 활성 예약 수와 일치했다.
- 미검증: 정원이 부족한 상황에서도 정원 초과가 항상 방지되는지 여부. 이번 테스트는 정원 20명에 요청 10개다.
- 미검증: 서로 다른 슬롯의 시간 충돌, 중복 취소, 취소와 생성의 경합.
- 한계: 시작 시점 동기화는 각 SQL의 실행 순서까지 고정하지 않는다. 한 번 테스트가 통과하더라도 동시성 안전성을 입증하지는 못한다.

## 4. 해결 설계 시 유지할 조건

아직 해결 방식을 선택하거나 운영 코드를 변경하지 않았다. 다음 조건을 만족하는 방식을 비교한다.

- 동일 회원·동일 슬롯의 활성 예약 중복을 막는다.
- 동일 회원의 시간이 겹치는 다른 슬롯 예약도 막는다.
- 문서의 활성 예약 기준 DB 유니크 요구사항을 충족하는지 확인한다.
- 취소 이력을 남기면서 같은 슬롯의 새 예약을 허용한다.
- 실패한 요청의 정원 확보는 rollback되고, 실제 예약 수와 reservedCount가 일치한다.
- 서로 다른 회원의 정상 예약까지 불필요하게 직렬화하지 않는지 검토한다.

주의할 설계 사항:

- `(user_id, slot_id)` 전체 유니크만 적용하면 취소 이력이 새 예약을 막을 수 있다.
- 동일 슬롯에 대한 유니크만으로는 서로 다른 슬롯의 시간 충돌을 막을 수 없다.
- 회원 단위 동시성 제어와 활성 예약 DB 제약의 역할을 구분해 검토한다.

## 5. 다음 재현: 서로 다른 슬롯의 시간 충돌

**상태: 미진행. 아래는 테스트 계획이며 실행 결과가 아니다.**

| 항목 | 계획 |
| --- | --- |
| 회원·티켓 | 같은 회원과 본인 소유 IN_USE 티켓 |
| 슬롯 A | 어트랙션 A, 16:00–16:30 |
| 슬롯 B | 어트랙션 B, 16:10–16:40 |
| 정원 | 두 슬롯 모두 충분한 정원 확보 |
| 요청 | 각 슬롯에 동시에 1개씩, 총 2개 |
| 기대 결과 | 성공 1건, 활성 예약 1건, 두 슬롯의 reservedCount 합계 1 |
| 추가 확인 | 거절된 요청은 시간 충돌로 실패하고 해당 슬롯 인원은 증가하지 않음 |

실행 후 이 절에 테스트 메서드, 실제 로그, DB 상태, 판정을 추가한다.

## 6. 개선 적용 및 재검증 기록

**상태: 미진행.** 해결 방식을 선택하고 구현한 뒤 작성한다.

| 기록 항목 | 작성할 내용 |
| --- | --- |
| 검토한 대안 | 각 방식의 적용 범위, 장단점, 선택·제외 이유 |
| 선택한 방식 | 동일 슬롯 중복과 시간 충돌을 각각 어떻게 방어하는지 |
| 코드·DB 변경 | 수정 파일, 적용한 제약 또는 동시성 제어, commit |
| 개선 후 결과 | 같은 조건의 테스트 로그와 DB 상태 |
| 회귀 검증 | 취소 후 재예약, 다른 회원의 정상 예약, 실패 시 정원 rollback |
| 남은 한계 | 아직 재현·검증하지 않은 경합과 후속 작업 |

### 완료 체크리스트

- [x] 동일 회원·동일 슬롯 동시 요청 재현
- [x] 개선 전 성공 건수·활성 예약 수·reservedCount 기록
- [ ] 서로 다른 슬롯의 시간 충돌 재현
- [ ] 해결 방식 비교 및 선택 근거 기록
- [ ] 해결 적용 및 DB 제약 요구사항 확인
- [ ] 동일 슬롯 동시 요청에서 성공 1건 검증
- [ ] 시간이 겹치는 다른 슬롯 동시 요청에서 성공 1건 검증
- [ ] 실패 요청의 정원 확보 rollback 검증
- [ ] 취소 후 같은 슬롯에 새 예약 생성 가능 여부 검증
- [ ] 서로 다른 회원의 정상 예약 회귀 검증
- [ ] 기존 예약 테스트 결과 기록
- [ ] 개선 전후 결과 비교 및 최종 한계 정리

## 7. 증거 보관과 추가 기록 방법

`build/` 아래 보고서는 다음 실행이나 clean으로 바뀔 수 있다. 이 문서에는 핵심 수치를 복사해 두었으며, XML·HTML 전체 파일이나 화면 이미지는 별도로 보관하지 않았다.

추가 실험은 기존 결과를 덮어쓰지 않고 다음 항목으로 이어서 기록한다.

```markdown
### 실험 제목 · YYYY-MM-DD
- 목적:
- 브랜치·commit 및 미커밋 변경:
- 환경·DB isolation level:
- 테스트 조건과 실행 명령:
- 기대 결과:
- 실제 로그 및 DB 상태:
- 판정과 분석:
- 다음 검증:
```
