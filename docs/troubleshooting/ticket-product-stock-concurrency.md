# 관리자 상품 변경과 주문·재고 복구 경합 트러블슈팅

> **KAN-95 · 재고 변경 유실 재현 및 잠금·version 검증 적용**
>
> 관리자 상품 삭제가 주문의 재고 차감을 덮어쓰는 문제를 실제 MySQL에서 재현했다.
> 관리자 수정·삭제에 상품 행 잠금을 적용하고, 오래된 관리자 수정 요청은 version 비교로 거절하도록 변경했다.
> 최신 로컬 XML에서 동시성 및 version 통합 테스트 8개의 통과를 확인했다.
> 기존 테이블·데이터를 대상으로 한 migration 검증은 아직 완료하지 않았다.

## 1. 무엇이 문제였나

주문 생성과 재고 복구는 `PESSIMISTIC_WRITE`로 상품을 조회했지만, 관리자 수정·삭제는 일반 `findById()`를 사용했다.

관리자 삭제가 상품 상태만 바꾼다고 생각하기 쉽지만, 이미 읽은 엔티티에는 이전 재고도 남아 있다. 다른 transaction이 재고를 차감한 뒤 관리자의 변경이 반영되면 재고가 이전 값으로 돌아갈 수 있었다.

실제 재현 결과는 다음과 같았다.

```text
관리자 삭제 transaction: 상품 조회 → 재고 10을 가진 엔티티 유지 → 대기
주문 transaction:       상품 잠금 → 2개 구매 → 재고 8 → commit
관리자 삭제 transaction: 대기 해제 → DELETED로 변경 → commit

최종 결과: 상품은 DELETED, 주문은 존재하지만 재고는 10
기대 결과: 상품은 DELETED, 주문 2개 차감을 보존하여 재고는 8
```

단순한 코드 중복이나 성능 문제가 아니라, 서로 다른 transaction 사이에서 변경이 사라지는 데이터 정합성 문제였다.

## 2. 요구사항과 이번에 결정한 정책

### 기존 문서에서 확인한 내용

출처는 읽기 전용 원본 `sources/비즈니스 규칙.md`이다. 원본 문서는 수정하지 않았다.

- **BR-TKT-10:** 재고는 방문 날짜별이 아니라 상품별 전체 날짜 통합 잔여 판매 수량이다.
- **BR-TKT-08:** 관리자의 가격 변경은 이후 주문부터 적용하며 기존 주문 금액은 변경하지 않는다.
- **BR-PAY-03:** 주문 항목에 주문 시점 단가를 보관한다.
- **BR-PAY-02:** 주문 금액은 서버에서 상품 가격과 수량으로 계산한다. 이 규칙 자체가 재고 잠금 방식을 명시하는 것은 아니다.

상품 잠금과 version 비교는 이 요구사항을 유지하기 위한 구현 방식이다. 문서에서 특정 잠금 기술을 의무화한 것으로 해석하지 않았다.

### 작업 중 합의한 정책

관리자 화면 조회 이후 상품이 변경됐다면, 그 화면의 오래된 version으로 보낸 수정 요청을 **409 Conflict**로 거절한다.

- 관리자 입력을 무조건 우선하여 최신 재고를 덮어쓰지 않는다.
- 클라이언트는 상품을 다시 조회하고, 새 version으로 수정 요청을 보낸다.
- 비교 대상은 상품 전체의 version이다. 재고뿐 아니라 상품 정보·판매 상태의 변경으로 version이 달라져도 오래된 수정 요청은 거절한다.
- 현재 재고 수정은 절대 수량을 지정하는 기존 방식을 유지한다. 증감 수량 방식의 새 API는 추가하지 않았다.

## 3. 수정 전 재현 방법과 확인 결과

### 재현 환경

- `@SpringBootTest`와 프로젝트의 `TestcontainersConfiguration` 사용
- 실제 MySQL `mysql:9.6`과 Redis 컨테이너 사용
- 실제 `TicketProductService.deleteTicketProduct()`와 `OrderService.createOrder()` 호출
- 관리자 삭제와 주문을 서로 다른 스레드·transaction에서 실행
- 초기 재고 10, 주문 수량 2
- 주문 가능한 방문 날짜는 KST 기준 다음 날로 준비

### 실행 순서를 제어한 이유

두 요청을 동시에 시작하기만 하면 항상 같은 순서로 실행되지는 않는다. 문제가 있는 코드도 우연히 정상 순서로 실행되면 테스트가 통과할 수 있다.

수정 전 테스트에서는 Repository spy로 관리자의 일반 조회 구간을 제어했다. 반환 데이터는 mock 상품이 아니라 해당 transaction의 `EntityManager.find()`로 실제 DB에서 읽은 엔티티였다.

1. 관리자 삭제가 재고 10을 읽는다.
2. `CountDownLatch`로 관리자 요청을 대기시킨다.
3. 별도 스레드에서 실제 주문 Service를 호출한다.
4. 주문 Service가 반환하고 transaction이 commit된 뒤, 새 transaction에서 재고 8을 확인한다.
5. 관리자 삭제를 재개하고 commit까지 기다린다.
6. 새 transaction에서 상품 상태, 재고, 주문 수량을 확인한다.

### 확인한 실패

2026-11-02 11:24:50 KST에 시작한 테스트 보고서에서 다음 실패를 확인했다.

```text
[주문 차감 유실 검증: 초기=10, 주문 commit 후=8, 관리자 삭제 commit 후=10]
expected: 8
 but was: 10
```

주문 및 상품 상태 검증이 아니라 최종 재고 검증에서 실패했다. 따라서 이 실행에서는 주문 차감이 관리자 삭제에 의해 덮어써지는 문제가 재현된 것으로 판단했다.

이전 XML 보고서는 재실행으로 교체됐다. 위 결과는 당시 실제 보고서에서 확인한 내용을 기록한 것이며, 수정 전 전체 XML·SQL 로그를 별도 보존한 것은 아니다.

### 재현 전에 발생한 준비 오류

아래 두 실패는 재고 문제의 재현 결과로 계산하지 않았다.

- **Docker 연결 실패:** `Could not find a valid Docker environment`로 ApplicationContext 초기화에 실패했다. Docker 실행 후 다시 테스트했다.
- **테스트 이메일 길이 초과:** 테스트 이메일이 51자였지만 `users.email`의 최대 길이는 50자였다. UUID 일부를 사용하여 이메일을 27자로 줄였다.

두 오류를 해결하고 테스트가 실제 경쟁 구간까지 도달한 뒤 재고 변경 유실을 확인했다.

## 4. 첫 번째 방어: 관리자 변경도 같은 상품 행을 잠근다

### Repository

[TicketProductRepository.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/repository/TicketProductRepository.java)에 단건 잠금 조회를 추가했다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT tp FROM TicketProduct tp WHERE tp.id = :ticketProductId")
Optional<TicketProduct> findByIdWithLock(@Param("ticketProductId") Long ticketProductId);
```

주문과 공통 재고 복구는 기존의 `findAllWithUpdateByIdInOrderById()`를 유지한다. 단건 조회와 일괄 조회라는 차이는 있지만, 같은 상품 행을 변경하면 DB 잠금으로 서로 조정된다.

### Service

[TicketProductService.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/service/TicketProductService.java)의 수정·삭제 경로에서 잠금 조회를 사용하도록 변경했다.

- `updateTicketProduct()`: 잠금 조회 후 검증·수정
- `deleteTicketProduct()`: 잠금 조회 후 삭제 상태로 변경
- 두 메서드의 `@Transactional` 유지
- 일반 목록·상세 조회는 기존 일반 조회 유지

일반 상세 조회와 공유하는 `getTicketProductById()` 자체를 잠금 조회로 바꾸지는 않았다.

### 삭제와 주문의 두 처리 순서

**삭제가 먼저 잠근 경우**

```text
삭제: 상품 잠금 → DELETED 변경 → commit
주문: 잠금 대기 → 최신 DELETED 확인 → ORDER_PRODUCT_NOT_ON_SALE로 거절
최종: 재고 10, 생성된 주문 없음
```

**주문이 먼저 잠근 경우**

```text
주문: 상품 잠금 → 2개 구매 → 재고 8 → commit
삭제: 잠금 대기 → 최신 재고 8 조회 → DELETED 변경 → commit
최종: 재고 8, 주문 1건·구매 수량 2
```

잠금 도입 이후에는 수정 전의 강제 실행 순서를 그대로 사용할 수 없다. 기존 테스트는 일반 조회 이후 주문을 먼저 commit시키는 재현 전용 구성이라, 잠금 도입에 맞춰 두 선행 순서를 검증하는 회귀 테스트로 변경했다.

## 5. 잠금만으로 오래된 화면 요청까지 막을 수는 없다

비관적 락은 서버에서 transaction을 실행하는 동안의 변경 순서를 조정한다. 관리자가 화면을 열어놓고 있는 시간까지 DB 잠금을 유지하는 것은 아니다.

```text
관리자 화면 조회: 재고 10
주문 완료:      재고 8
관리자 수정 요청: 가격을 바꾸면서 예전 stock=10도 전송
```

관리자 요청이 상품을 잠그고 최신 재고 8을 읽어도, 요청의 `stock=10`을 그대로 대입하면 재고가 다시 10이 된다. 이는 transaction 실행 순서와 별도로 해결해야 하는 오래된 요청 문제다.

## 6. 두 번째 방어: 조회 당시 version과 최신 version을 비교한다

### 엔티티와 API 변경

[TicketProduct.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/entity/TicketProduct.java)에 다음 필드를 추가했다.

```java
@Version
private Long version;
```

JPA가 관리하는 version을 생성자나 비즈니스 메서드에서 직접 증가시키지 않는다.

- [SearchTicketProductResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/SearchTicketProductResponse.java): 조회 응답에 `Long version` 추가
- [UpdateTicketProductRequest.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/UpdateTicketProductRequest.java): `@NotNull`, `@Min(0)`을 적용한 `Long version` 추가
- [UpdateTicketProductResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/UpdateTicketProductResponse.java): 수정 응답에 갱신된 version 추가

초기 version인 0은 유효하다. 기존 클라이언트도 수정 요청에 조회한 version을 보내도록 반영해야 하며, 누락된 요청은 400으로 거절된다.

### 수정 처리 순서

```text
상품 잠금 조회
→ 요청 version과 엔티티 version의 값 비교
→ 다르면 CONCURRENT_UPDATE_CONFLICT(409)
→ 같으면 상품 수정
→ flush
→ 응답 DTO 생성
→ transaction commit
```

현재 비교 코드는 다음과 같다.

```java
if (!ticketProduct.getVersion().equals(request.version())) {
    throw new BusinessException(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
}
```

`Long`의 객체 동일성이 아니라 숫자 값을 비교한다. 실제 DB에 저장된 상품의 version과 API 검증을 통과한 요청을 비교하는 경로이며, 단위 테스트의 엔티티 fixture에도 version을 준비했다.

### 비관적 락과 낙관적 락의 역할 구분

- `PESSIMISTIC_WRITE`: 현재 실행 중인 변경 transaction의 순서를 조정한다.
- `@Version`: JPA가 변경 번호를 관리하고, 엔티티 갱신 시 version 기반 충돌 검사를 수행한다.
- **요청 version의 명시적 비교:** 관리자 화면 조회 이후의 변경을 감지한다. `@Version`을 붙이는 것만으로 이 비교가 자동 구현되지는 않는다.

이번 변경은 비관적 락을 제거하고 낙관적 락으로 교체한 것이 아니다. 기존 잠금에 version 기반의 오래된 요청 검사를 더했다.

### 응답 생성 전에 flush하는 이유

엔티티 필드만 수정한 시점에는 version이 아직 이전 값일 수 있다. `ticketProductRepository.flush()` 이후 응답을 생성하여 실제 변경으로 갱신된 version을 담는다.

- flush는 commit이 아니며, 이후 transaction이 실패하면 rollback될 수 있다.
- 실제 변경이 없는 요청은 version이 증가하지 않을 수 있다.
- 단위 테스트에서 version 증가를 임의로 흉내 내는 대신, 실제 DB 통합 테스트에서 응답과 DB의 version 일치를 검증했다.

## 7. 수정 후 테스트 결과

### 최신 XML에서 직접 확인한 결과

[TicketProductStockConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/service/TicketProductStockConcurrencyIntegrationTest.java)의 로컬 XML 보고서를 확인했다.

```text
시작 시각: 2026-11-02 16:12:55 KST
tests=8, failures=0, errors=0, skipped=0
```

검증한 8개 실행 케이스는 다음과 같다.

1. **삭제 선행:** 주문 거절, DELETED, 재고 10, 주문 없음
2. **주문 선행 후 삭제:** 주문·삭제 성공, DELETED, 재고 8, 구매 수량 2
3. **최신 version 수정:** 변경 성공, 응답 version 증가 및 DB version과 일치
4. **관리자 가격 수정 선행:** 주문이 수정된 단가 60,000원을 사용, 주문 금액 120,000원, 재고 8
5. **주문 선행 후 관리자 수정:** 오래된 요청을 충돌로 거절, 기존 가격과 차감된 재고 8 유지
6. **재고 복구 후 재요청:** 재고 12·version 증가, 오래된 요청 거절, 최신 version 재조회 후 수정 허용
7. **공통 재고 복구 선행:** 관리자 요청 충돌로 거절, 재고 12 유지
8. **관리자 재고 수정 선행:** 재고 20으로 수정한 뒤 2개 복구, 최종 재고 22

### 기존 테스트 보완 및 추가 검증

아래 테스트들도 변경된 생성자·mock·API 계약에 맞췄다.

- [TicketProductServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/service/TicketProductServiceTest.java): 잠금 조회 mock, 요청 version, 불일치 시 상품 정보와 재고 미변경 검증
- [TicketProductDeleteServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/service/TicketProductDeleteServiceTest.java): 잠금 조회 mock 및 호출 검증
- [TicketProductDetailServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/service/TicketProductDetailServiceTest.java): 조회 DTO의 version 매핑
- [TicketProductSearchServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/service/TicketProductSearchServiceTest.java): 목록 DTO의 version 매핑
- [TicketProductDetailControllerTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/controller/TicketProductDetailControllerTest.java): 상세 응답의 version 포함
- [TicketProductSearchControllerTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/controller/TicketProductSearchControllerTest.java): 목록 응답의 version 포함
- [TicketProductControllerIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/ticketProduct/controller/TicketProductControllerIntegrationTest.java): 수정 응답의 version, version 누락·음수의 400, 오래된 요청의 409 검증

사용자는 `TicketProduct` 관련 테스트가 모두 통과했다고 보고했다. 문서 작성 시점에 남아 있는 XML은 위 통합 테스트 8개 결과이므로, 관련 테스트 전체의 정확한 실행 수나 프로젝트 전체 테스트 통과로 확대해서 기록하지 않았다.

### 테스트 실행 방법

Docker 실행 후 Git Bash에서 각 명령어를 한 줄로 실행한다.

```bash
./gradlew test --tests "*TicketProductStockConcurrencyIntegrationTest" --rerun-tasks
```

```bash
./gradlew test --tests "*TicketProduct*" --rerun-tasks
```

문서 작성 과정에서는 위 명령어를 실행하지 않았다. 기존 보고서를 읽어서 확인한 결과와 사용자가 알려준 결과를 구분했다.

## 8. 기존 DB migration 준비와 미검증 상태

프로젝트의 기본 설정은 `spring.jpa.hibernate.ddl-auto: validate`다. 엔티티에 필드를 추가해도 기존 DB 컬럼을 자동으로 생성하지 않는다.

[KAN-95 migration SQL](../sql/kan-95-ticket-product-version-migration.sql)을 작성했다.

```sql
ALTER TABLE ticket_products
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

위 문장은 대상 확인 없이 단독 실행할 명령이 아니다. 파일에는 대상 DB·테이블·기존 컬럼 확인, 적용 전후 상품 수와 초기값 확인, 백업 및 쓰기 중지 안내가 포함되어 있다.

### 확인한 적용 상태

- SQL 파일 작성 완료
- 사용자가 올바른 로컬 DB 이름을 확인하고 새로고침했지만 `ticket_products` 테이블이 없다고 보고함
- 해당 로컬 DB에는 migration을 적용하지 않음
- 작성 과정에서 DB 접속이나 DDL 실행을 수행하지 않음
- 기존 테이블·데이터가 있는 환경에서의 적용 검증은 미완료

테스트의 `create-drop`으로 version 컬럼이 포함된 새 스키마를 만든 것과, 기존 데이터가 있는 테이블에 컬럼을 추가하는 migration은 서로 다른 검증이다.

### 실제 적용 시 확인할 사항

1. 기존 `ticket_products` 테이블이 있는 적용 대상 DB인지 확인한다.
2. 관리자·주문·취소·결제 실패·환불의 상품 변경을 중지하고 테이블을 백업한다.
3. version 컬럼이 없는 경우에만 컬럼 추가를 실행한다. 이미 있는 값을 0으로 초기화하지 않는다.
4. 컬럼 정의, 기존 상품 수와 초기 version 값을 확인한다.
5. 새 version을 사용하는 애플리케이션을 기동한 뒤 작업을 재개한다.

MySQL DDL에는 implicit commit이 있으므로 일반 transaction rollback을 복구 수단으로 가정하지 않는다. version을 갱신하지 않는 구버전 애플리케이션과 동시 운영하지 않는다.

## 9. 검증 범위와 남은 한계

- 동시성 테스트는 실제 Service와 Repository를 사용한다. 최종 테스트에는 수정 전 재현용 Repository spy가 없다.
- `TransactionTemplate`로 선행 transaction의 commit 시점을 제어한다. 실제 HTTP 요청을 동시에 보내는 테스트는 아니다.
- 후행 요청이 500ms 동안 완료되지 않는 것을 확인하지만, MySQL 내부 lock wait 정보를 직접 수집한 것은 아니다. 최종 결과와 거절 사유도 함께 검증한다.
- 경쟁 테스트의 재고 복구는 주문 취소·결제 실패·환불이 공유하는 `updateTicketProductsStockIncrease()`를 호출한다. PG 환불 전체 흐름과 결제·주문·상품을 함께 잠그는 모든 조합을 검증한 것은 아니다.
- 두 transaction의 통제된 실행 순서를 검증했다. 대량 요청 처리량·대기 시간·모든 deadlock 가능성까지 검증한 것은 아니다.
- JPA 엔티티 변경을 통하지 않는 별도 SQL이나 bulk UPDATE가 추가되면 version 갱신을 우회하는지 다시 확인해야 한다.
- 기존 데이터 환경에서 migration 적용과 새 클라이언트의 version 전달 연동은 별도 확인이 필요하다.

## 10. 작업 상태

- [x] 관리자 삭제가 주문의 재고 차감을 덮어쓰는 문제 재현
- [x] 관리자 수정·삭제에 상품 행 잠금 조회 적용
- [x] 오래된 수정 요청을 409로 거절하는 정책 결정
- [x] 엔티티 및 조회·수정 API에 version 연결
- [x] flush 이후 version을 수정 응답에 포함
- [x] 실제 MySQL 통합 테스트 8개 통과 확인
- [x] 관련 기존 테스트 변경 및 사용자 통과 보고 확인
- [x] 기존 DB 적용용 SQL 작성
- [ ] 기존 테이블·데이터가 있는 환경에서 migration 적용 검증
- [ ] 실제 클라이언트의 version 조회·전달·409 후 재조회 흐름 확인

작성 기준: 2026-11-02, `fix/KAN-95-ticket-product-stock-concurrency`, HEAD `613ca3c`.
이 문서는 해당 시점의 코드·확인한 보고서·대화 중 합의한 정책을 기록한다.
