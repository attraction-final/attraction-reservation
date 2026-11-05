# 미사용 티켓의 null·자기 상태 전이 허용 트러블슈팅

> **KAN-106 · 티켓 상태 전이 조건과 변경 경로 보완**
>
> UNUSED의 전이 조건이 USED만 제외하여 null과 자기 상태도 허용하는 문제를 확인했다.
> 허용할 다음 상태를 명시하고 일반 전이 메서드를 private으로 제한했다.
> 상태 전이·환불 엔티티 테스트와 티켓·환불 회귀 테스트를 검증했다.

## 1. 문제와 영향 범위

수정 전 [TicketStatus.java](../../src/main/java/com/example/attractionreservation/domain/ticket/entity/enums/TicketStatus.java)의 UNUSED 조건은 다음과 같았다.

```java
return nextStatus != USED;
```

이 조건은 다음 상태가 USED일 때만 false를 반환한다. 따라서 문서상 허용되는 IN_USE·REFUNDED·EXPIRED뿐 아니라 다음 값도 true로 판단했다.

- `UNUSED`: 자기 상태로의 전이
- `null`: 유효한 상태가 아닌 값

[Ticket.java](../../src/main/java/com/example/attractionreservation/domain/ticket/entity/Ticket.java)의 일반 전이 메서드는 enum의 판단을 통과하면 입력값을 그대로 상태에 대입했다.

```java
public void statusTransition(TicketStatus newStatus) {
    if (!this.status.canTransitTo(newStatus)) {
        throw new BusinessException(ErrorCode.INVALID_TICKET_STATUS_TRANSITION);
    }

    this.status = newStatus;
}
```

코드상 UNUSED 티켓에 `statusTransition(null)`을 호출하면 상태가 null이 될 수 있었다. DB 컬럼의 `nullable=false`는 DB 반영 시의 제약이며, Java 객체에 null이 대입되는 시점까지 보호하지 않는다.

또한 일반 전이 메서드가 public으로 공개되어 있어 외부 코드가 IN_USE·EXPIRED 등으로 직접 변경하면 전용 메서드의 날짜·시간 검증과 시각 기록을 우회할 수 있었다.

**검토 당시 main 코드에서 확인한 호출처는 같은 클래스의 refund() 내부뿐이었다.** 외부 API가 실제로 이 우회를 노출하거나 null을 저장한 사례를 확인한 것은 아니다. 엔티티가 공개한 변경 경로와 enum의 방어를 보완하는 작업이다.

## 2. 문서 기준

읽기 전용 원본 `sources/비즈니스 규칙.md`의 입장권 상태 전이표를 확인했다. 원본 문서는 변경하지 않았다.

허용되는 상태 간 전이는 다음 네 가지다.

```text
UNUSED → IN_USE
UNUSED → REFUNDED
UNUSED → EXPIRED
IN_USE → USED
```

USED·EXPIRED·REFUNDED는 다른 상태로 변경할 수 없다. null과 자기 상태는 허용 전이에 포함하지 않는다.

상태 간 전이가 가능하다는 것과 업무 조건을 만족한다는 것은 별개다. 입장 시 날짜·시간 확인, 환불 가능 상태 확인, 폐장 시각 확인은 기존 전용 메서드에서 수행한다.

## 3. 수정 전 테스트로 확인

[TicketStatusTest.java](../../src/test/java/com/example/attractionreservation/domain/ticket/entity/enums/TicketStatusTest.java)에 최초 재현 테스트 두 개를 작성했다.

```text
UNUSED.canTransitTo(null)
기대: false / 수정 전 조건의 결과: true

UNUSED.canTransitTo(UNUSED)
기대: false / 수정 전 조건의 결과: true
```

사용자가 수정 전 테스트 실행 후 실패를 확인했다. 이 테스트는 enum의 잘못된 허용 판단을 재현하며, 실제 DB에 null 상태를 저장하는 테스트는 아니다.

초기 테스트는 이후 전체 상태 조합을 검사하도록 확장했다. 최초 실패 리포트 원본을 별도로 보관한 것은 아니다.

## 4. 해결 A: 허용할 다음 상태를 명시

UNUSED의 조건을 다음과 같이 변경했다.

```java
return nextStatus == IN_USE
        || nextStatus == REFUNDED
        || nextStatus == EXPIRED;
```

세 상태 중 하나인 경우에만 true가 된다. null과 UNUSED는 어느 조건에도 일치하지 않으므로 false가 된다.

- IN_USE는 기존처럼 USED만 허용한다.
- USED·EXPIRED·REFUNDED는 기존처럼 모든 변경을 거부한다.
- DB 스키마와 enum 값은 변경하지 않았다.

## 5. 해결 B: 일반 전이 메서드 공개 범위 제한

main·test 코드에서 `statusTransition()` 호출처를 확인한 결과, Ticket 내부의 `refund()`에서만 사용하고 있었다.

따라서 접근 제어자를 public에서 private으로 변경했다.

```java
private void statusTransition(TicketStatus newStatus) {
    if (!this.status.canTransitTo(newStatus)) {
        throw new BusinessException(ErrorCode.INVALID_TICKET_STATUS_TRANSITION);
    }

    this.status = newStatus;
}
```

외부에서는 다음 공개 업무 메서드를 사용하도록 변경 경로를 제한했다.

- `entryTicket(now)`: 상태·날짜·이용 시간 검증 후 IN_USE로 변경하고 enteredAt 기록
- `refund()`: UNUSED 여부를 검사한 뒤 REFUNDED로 변경
- `expireIfClosed(now)`: 폐장 시각을 확인하여 EXPIRED 또는 USED로 변경하고 endTime 기록

입장·환불·종료 메서드의 기존 업무 조건과 시각 기록 로직은 변경하지 않았다. 일반 메서드의 private 변경 자체는 DB 동시성 제어를 추가하는 작업이 아니다.

## 6. 테스트 보강

### TicketStatusTest: 30개 case

비즈니스 규칙의 전이표를 기준으로 다음을 검사한다.

- 모든 현재 상태의 null 전이 거부: 5개
- 모든 현재 상태의 자기 상태 전이 거부: 5개
- 명시된 허용 전이: 4개
- 나머지 서로 다른 상태 간 미허용 전이: 16개

5개 상태 사이의 모든 조합과 null 입력을 포함한다.

### TicketRefundTest: 6개 case

[TicketRefundTest.java](../../src/test/java/com/example/attractionreservation/domain/ticket/entity/TicketRefundTest.java)를 추가했다.

- 종일권·오후권의 UNUSED 티켓 환불 성공: 2개
- IN_USE·USED·EXPIRED·REFUNDED 티켓 환불 거부: 4개
- 환불 성공·거부 이후 입장 시각과 종료 시각 유지 확인
- 환불 거부 시 기존 상태 유지 확인

테스트 fixture의 상태는 입장·종료·환불 공개 메서드로 준비한다. private 전이 메서드를 reflection으로 호출하지 않는다.

이 두 클래스는 DB 없이 실행하는 Unit Test다. 사용자 실행으로 두 클래스의 통과를 확인했다.

## 7. 관련 기능 회귀 검증

사용자가 실행한 명령어:

```bash
# 상태 전이와 환불 엔티티 검증
./gradlew test --tests "*TicketStatusTest" --tests "*TicketRefundTest" --rerun-tasks

# 티켓 관련 회귀 검증
./gradlew test --tests "*Ticket*" --rerun-tasks --no-daemon --max-workers=1

# 환불 관련 회귀 검증
./gradlew test --tests "*Refund*" --rerun-tasks --no-daemon --max-workers=1
```

- 사용자가 티켓·환불 회귀 테스트를 각각 실행하여 모두 통과했다고 확인했다.
- 마지막 실행 후 남아 있는 환불 XML 리포트에서는 **20개 Test Suite, 320개 테스트, 실패·오류·건너뜀 0개**를 확인했다.
- Gradle 리포트는 다음 실행으로 교체되므로 현재 환불 XML에 티켓 실행 결과까지 합산되어 있는 것은 아니다. 별도 실행 건수를 합친 전체 테스트 수는 제시하지 않는다.
- 문서 작성 중 테스트를 다시 실행하지 않았다.

## 8. 검증 범위와 한계

- enum의 허용·미허용 판단과 공개 환불 메서드의 상태·시각 보존을 검증했다.
- 코드 검토로 일반 전이 메서드가 private이며 외부 호출처가 없음을 확인했다.
- 외부 API에서 null 전이가 발생한 사례나 운영 DB의 null 저장을 재현한 것은 아니다.
- 상태 전이 제한과 기존 입장·환불·종료 흐름의 회귀를 확인한 범위이며, 새로운 동시성 제어나 환불 정책을 도입하지 않았다.
- HTTP·Security·실제 PG 연동을 새 엔티티 Unit Test가 직접 검증하는 것은 아니다.

## 9. 배운 점

금지할 상태 하나만 제외하는 조건은 의도하지 않은 값까지 허용할 수 있다. 이번에는 허용 상태를 명시하여 null·자기 상태가 통과하는 빈틈을 막았다.

또한 상태 변경에는 상태값 외에 날짜·시간 검증과 이력 기록이 함께 필요하다. 실제 사용처에 맞춰 일반 변경 메서드의 공개 범위를 줄여 전용 업무 메서드를 통해 처리하도록 보완했다.
