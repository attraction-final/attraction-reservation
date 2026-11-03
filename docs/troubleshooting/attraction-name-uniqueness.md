# 어트랙션 이름의 동시 등록·수정 중복 저장 트러블슈팅

> **KAN-103 · 삭제된 이름의 재사용을 허용하면서 이름 유일성 보장**
>
> 실제 MySQL에서 동일 이름의 동시 등록 요청이 모두 성공하고, 같은 이름의 행이 2개 저장되는 문제를 재현했다.
> Generated Column과 UNIQUE 제약으로 DB에서도 유일성을 보장하고, 등록·수정의 이름 중복 제약 위반을 기존 업무 예외로 변환했다.
> 관련 회귀 테스트는 52개 Test Suite, 370개 테스트가 통과했다. 기존 DB에 대한 migration 적용은 아직 검증하지 않았다.

## 1. 문제와 확인 범위

등록과 이름 수정에는 이미 사전 중복 검사가 있었다.

- 등록: `existsByNameAndDeletedAtIsNull()`
- 수정: `existsByNameAndIdNotAndDeletedAtIsNull()`

순차 요청에서는 중복을 안내할 수 있지만, 검사와 저장이 하나의 원자적 작업은 아니었다.

```text
요청 A                              요청 B
----------------------------------------------------------
같은 이름이 없는지 조회 → 없음
                                    같은 이름이 없는지 조회 → 없음
같은 이름으로 INSERT
                                    같은 이름으로 INSERT
commit                              commit
→ 같은 이름의 어트랙션 2개 저장
```

검토한 엔티티와 저장소 SQL에서는 이름의 UNIQUE 제약을 찾지 못했다. 이후 Testcontainers로 새로 만든 MySQL 스키마에서 중복 저장을 실제로 재현했다.

이 결과가 기존 팀·운영 DB에도 제약이 없다는 증거는 아니다. 사용자가 확인한 로컬 DB에는 `attractions` 테이블 자체가 없었고, 기존 테이블이 있는 다른 DB의 제약은 확인하지 않았다.

## 2. 비즈니스 정책 확인

읽기 전용 원본 `sources/비즈니스 규칙.md`에서 다음 규칙을 확인했다.

- **BR-ATR-01:** 어트랙션명은 공원 내에서 유일해야 하며, 위반 시 등록·수정을 거부한다.
- **BR-COM-07:** 어트랙션은 소프트 삭제하며, 조회 시 기본으로 제외한다.

원본에는 삭제된 이름의 재사용 여부가 명시되어 있지 않았다. 현재 사전 검사는 삭제된 행을 제외하고 있었다.

이번 작업에서는 사용자가 팀원들과 논의한 후 **삭제된 어트랙션의 이름을 재사용할 수 있다**고 확정했다. 원본 참고 문서는 변경하지 않았으며, 아래 구현과 테스트는 이 합의에 따른다.

```text
삭제되지 않은 행끼리 같은 이름 사용 → 금지
삭제된 행과 새 행의 이름이 같음    → 허용
같은 이름을 가진 삭제 이력 여러 개 → 허용
```

## 3. 기존 행 잠금으로 해결되지 않는 이유

KAN-102에서 일반 수정에 비관적 잠금 조회를 적용했다. 하지만 이번 문제는 같은 행의 변경 유실과 보호 대상이 다르다.

- 같은 이름으로 새로 등록할 때는 기존 어트랙션 행이 없다.
- 서로 다른 어트랙션을 같은 이름으로 수정하면 각각 다른 행을 잠근다.
- 각 행을 잠그더라도 이름 전체의 유일성을 보장하는 것은 아니다.

따라서 기존 행 잠금을 유지하면서, 이름 중복은 별도의 DB 제약으로 방어했다.

## 4. 재현 테스트 구성

[AttractionNameConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/attraction/service/AttractionNameConcurrencyIntegrationTest.java)를 작성했다.

- `@DataJpaTest`, `@ActiveProfiles("test")`
- Testcontainers MySQL `mysql:9.6`
- `ddl-auto=create-drop`, Hikari 최대 커넥션 4개
- 기본 테스트 트랜잭션은 `NOT_SUPPORTED`
- 실제 Service·Repository와 실제 DB 제약 사용
- Redis 캐시 삭제만 mock으로 제외
- 테스트별 UUID 이름 사용 및 해당 테스트가 만든 행만 정리

동시 요청 테스트에서는 두 요청이 실제 중복 검사를 마칠 때까지 `CountDownLatch`로 대기한다. 단순히 동시에 시작하는 것보다, 두 요청이 모두 사전 검사를 통과하는 조건을 명확하게 만들기 위한 제어다.

Repository Spy의 중복 검사 호출 안에서 실제 JPQL COUNT를 실행하며, 결과를 임의로 false로 고정하지 않는다. 두 검사 결과가 모두 0임을 확인한 뒤 각 요청이 저장을 진행한다.

Service 프록시 호출이 반환될 때까지 기다리므로, 성공 건수는 트랜잭션 commit 완료 기준으로 집계한다. 최종 DB 확인은 별도 트랜잭션에서 수행한다.

## 5. 첫 재현: 같은 이름의 동시 등록

최초 테스트 리포트 시작 시각은 **2026-11-03 19:21:47 KST**였다.

```text
등록 성공: 기대 1건 / 실제 2건
등록 거부: 기대 1건 / 실제 0건
동일 이름의 활성 행: 기대 1개 / 실제 2개
```

테스트 1개 안의 검증 3개가 실패했고, 실행 오류는 없었다. 사전 중복 검사만으로는 동시 등록을 막지 못한다는 점을 확인했다.

## 6. DB 제약 설계

`name` 전체에 UNIQUE를 적용하면 소프트 삭제된 행의 이름도 재사용할 수 없으므로, 합의한 정책과 맞지 않았다.

[Attraction.java](../../src/main/java/com/example/attractionreservation/domain/attraction/entity/Attraction.java)에 `active_attraction` 보조 컬럼을 선언했다.

```sql
active_attraction INT GENERATED ALWAYS AS (
    CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END
)
```

- 삭제되지 않은 행은 1로 계산한다.
- 삭제된 행은 NULL로 계산한다.
- 사전 중복 검사와 동일하게 `deleted_at`을 기준으로 판단한다.
- JPA에서는 `Integer`로 매핑하고 `insertable=false`, `updatable=false`로 선언한다.
- 생성자나 삭제 메서드에서 이 필드에 값을 직접 대입하지 않는다.

여기에 다음 복합 UNIQUE 제약을 선언했다.

```sql
CONSTRAINT uk_attraction_name_active UNIQUE (name, active_attraction)
```

같은 이름의 삭제되지 않은 행은 `(이름, 1)`로 충돌한다. 삭제된 행은 `(이름, NULL)`이므로 MySQL의 NULL을 포함한 UNIQUE 키 처리에 따라 같은 이름의 삭제 이력을 여러 건 보존할 수 있다.

이름 동일성은 실제 컬럼의 collation을 따른다. 이번 테스트는 완전히 같은 문자열을 사용했으며, 대소문자·공백 정규화 정책을 새로 정의한 것은 아니다.

## 7. 두 번째 확인: 저장은 막혔지만 업무 예외 변환 실패

제약 추가 후 등록 테스트를 다시 실행한 결과, **2026-11-03 20:03:08 KST** 리포트에서 다음을 확인했다.

- 등록 성공 1건, 거부 1건, 저장 1건 검증은 통과했다.
- 업무 예외 타입 검증만 실패했다.

```text
기대: BusinessException(ATTRACTION_NAME_DUPLICATED)
실제: DataIntegrityViolationException
제약: attractions.uk_attraction_name_active
```

DB의 중복 저장 방지는 작동했지만, 현재 전역 처리에서는 변환되지 않은 `DataIntegrityViolationException`을 500으로 응답한다. 이름 중복은 기존 업무 오류로 안내하기 위해 별도 판별이 필요했다.

### 등록 경로 처리

- 저장을 `saveAndFlush()`로 변경하고 try 안에서 수행한다.
- `DataIntegrityViolationException`을 catch한다.
- 이름 중복 제약이면 `BusinessException(ATTRACTION_NAME_DUPLICATED)`을 던진다.
- 다른 제약이면 원래 예외를 다시 던진다.

기존 IDENTITY 매핑에서는 INSERT가 save 시점에 실행될 수 있으므로, try 바깥에서 먼저 save하는 형태를 사용하지 않았다.

## 8. 세 번째 확인: 동일 이름 동시 수정

서로 다른 이름으로 저장한 어트랙션 두 개를 같은 새 이름으로 수정하는 테스트를 추가했다. 각 요청은 자기 행을 잠근 상태에서 실제 이름 중복 검사를 수행하고, 두 검사가 끝난 뒤 변경을 반영한다.

**2026-11-03 21:21:41 KST** 리포트 결과:

- 등록 테스트는 통과했다.
- 수정 성공 1건 및 동일 이름 행 1개 검증은 통과했다.
- 실패한 수정 요청의 기존 이름 유지 검증도 통과했다.
- 수정 경로에서 `DataIntegrityViolationException`이 그대로 반환되어 업무 예외 타입 검증만 실패했다.

수정 경로의 기존 `flush()`에도 등록과 동일한 예외 변환을 적용했다. 엔티티는 이미 영속 상태이므로 새 save 호출은 추가하지 않았다.

## 9. 공통 판별 메서드로 분리

[AttractionService.java](../../src/main/java/com/example/attractionreservation/domain/attraction/service/AttractionService.java)의 `isAttractionNameDuplicate()`로 판별 로직을 분리했다.

1. catch로 받은 예외에서 시작한다.
2. `getCause()`로 원인 예외를 순회한다.
3. Hibernate의 `org.hibernate.exception.ConstraintViolationException`에서 제약 이름을 확인한다.
4. 이름이 `uk_attraction_name_active`와 같거나 `.uk_attraction_name_active`로 끝나면 true를 반환한다.
5. 끝까지 찾지 못하면 false를 반환한다.

제약 이름이 null인 경우를 먼저 확인하고, `other_uk_attraction_name_active`처럼 일부 문자열만 같은 다른 제약은 이름 중복으로 처리하지 않는다.

판별 메서드는 boolean만 반환하고, 등록·수정의 catch에서 예외 변환을 결정한다. DB 처리 실패 이후 같은 트랜잭션으로 재조회하거나 저장을 재시도하지 않으며, 예외를 전파하여 rollback되도록 했다.

## 10. 최종 검증

### MySQL 통합 테스트 5개

- **동일 이름 동시 등록:** 성공 1건, 이름 중복 업무 예외 1건, 활성 행 1개.
- **서로 다른 행의 동일 이름 동시 수정:** 성공 1건, 이름 중복 업무 예외 1건, 실패한 행은 기존 이름 유지.
- **삭제된 이름으로 신규 등록:** 등록 성공, 기존 삭제 이력과 삭제 시각 유지.
- **다른 어트랙션을 삭제된 이름으로 수정:** 수정 성공, 기존 삭제 이력 유지.
- **재사용한 이름을 다시 삭제하고 재등록:** 여러 삭제 이력과 활성 행 1개 공존.

재사용 테스트에서는 별도 트랜잭션에서 DB가 계산한 보조 컬럼도 확인했다. 삭제된 행은 NULL, 삭제되지 않은 행은 1이었다.

### Unit Test 보완

[AttractionServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/attraction/service/AttractionServiceTest.java)의 기존 등록 mock과 사전 중복 거절 검증을 `saveAndFlush()`에 맞췄다.

예외 처리 테스트 메서드 6개를 추가했으며, parameterized case를 포함하면 12건이다.

- 등록·수정 각각 이름 중복 제약의 업무 예외 변환.
- 테이블 접두사가 있는 제약 이름도 처리.
- 다른 제약·유사한 이름·null 이름은 원래 예외 유지.
- Hibernate 제약 예외가 없는 경우도 원래 예외 유지.
- 수정 실패 시 캐시 삭제를 요청하지 않음.

Unit Test만으로 DB rollback을 증명한 것은 아니다. 수정 실패 후 기존 이름 유지 여부는 실제 MySQL 통합 테스트에서 검증했다.

### 실행 결과

사용자가 실행한 명령어:

```bash
# Unit Test 및 동시성·재사용 통합 테스트
./gradlew test --tests "*AttractionServiceTest" --tests "*AttractionNameConcurrencyIntegrationTest" --rerun-tasks

# 관련 기능 회귀 검증
./gradlew test --tests "*Attraction*" --tests "*Reservation*" --rerun-tasks
```

마지막 실행 후 생성된 XML 리포트에서 확인한 결과:

```text
Test Suite: 52
Tests:      370
Failures:   0
Errors:     0
Skipped:    0

AttractionNameConcurrencyIntegrationTest: 5개 통과
AttractionServiceTest:                  35개 통과
```

두 클래스의 마지막 XML 시작 시각은 각각 **2026-11-03 22:29:23 KST**, **22:29:38 KST**다. 테스트는 사용자가 실행했고, 문서 작성 시 리포트를 읽어 확인했다. 전체 프로젝트 테스트의 통과를 의미하지는 않는다.

과거 실패 XML은 마지막 실행으로 덮어써졌으므로, 위 재현 기록은 당시 읽어 확인한 결과를 정리한 것이다.

## 11. 기존 DB용 migration 준비

[kan-103-attraction-name-unique-migration.sql](../sql/kan-103-attraction-name-unique-migration.sql)을 추가했다.

SQL에는 다음 단계를 담았다.

1. 대상 DB·테이블·기존 인덱스 확인.
2. 삭제되지 않은 행의 이름 중복 확인.
3. Generated Column과 복합 UNIQUE 제약 추가.
4. 계산식·인덱스·보조 컬럼 값·중복 여부 확인.

이 파일은 자동 실행되지 않는 수동 migration 스크립트다. 기존 테이블이 있는 DB에 적용할 때는 쓰기 작업을 중지하고 백업한 뒤 단계별 결과를 확인해야 한다.

이미 컬럼·제약이 있거나 name 전체의 UNIQUE 제약이 있다면, 이 스크립트를 그대로 추가 실행하지 않고 기존 구조에 맞는 처리 방안을 검토한다. 기존 중복 데이터의 삭제·이름 변경을 자동으로 수행하지 않는다.

**사용자가 확인한 로컬 DB에는 테이블이 없어 실행하지 않았다.** 새 스키마 생성은 통합 테스트로 검증했지만, 기존 데이터가 있는 DB의 ALTER 적용·배포 전환은 미검증이다.

## 12. 검증 범위와 남은 확인

- 실제 MySQL에서 중복 저장 방지, Service 예외 변환, 실패한 수정의 rollback, 삭제된 이름 재사용을 확인했다.
- HTTP·Spring Security·실제 Redis 캐시 처리는 이번 통합 테스트에 포함하지 않았다.
- HTTP 응답의 상태 코드와 JSON 구조는 이번 테스트에서 직접 검증하지 않았다.
- 팀·운영 DB의 기존 제약과 중복 데이터, 실제 migration 적용은 배포 전 확인이 필요하다.
- 삭제와 재사용이 동시에 겹치는 경우, 대량 요청의 처리량, 모든 잠금 경로의 deadlock·timeout을 검증한 것은 아니다.
- 테스트는 동일 문자열을 사용하며, 별도 이름 정규화 정책은 추가하지 않았다.

## 13. 배운 점

사전 중복 검사는 사용자에게 빠르게 오류를 안내하는 데 유용하지만, 검사와 저장 사이의 동시 요청까지 막지는 못한다.

이번에는 사전 검사를 유지하면서 DB 제약으로 최종 유일성을 보장했다. 소프트 삭제 정책에 맞는 제약 설계와, 실제 제약 위반을 업무 오류로 변환하는 처리까지 함께 맞춰야 기능이 완성된다는 점을 확인했다.
