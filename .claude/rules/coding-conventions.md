# 코드 스타일과 네이밍

- 클래스명은 `PascalCase` (`ReservationService`), 변수·메서드는 `camelCase` (`createReservation()`), 상수는 `UPPER_SNAKE_CASE` (`MAX_CAPACITY`)를 사용한다.
- 의미 없는 한 글자 변수명을 피하고 의미가 명확한 이름을 사용한다.
- 클래스 내부에서만 사용하는 메서드는 `private`으로 선언한다.
- 객체 타입 변환에는 `stream().map()`을 사용하고, 단순 목록 복사에는 불필요한 Stream을 사용하지 않는다.
- 단순한 메서드 호출은 람다보다 메서드 참조를 우선한다.

```java
List<ReservationResponse> responses = reservations.stream()
    .map(ReservationResponse::from)
    .toList();
```

## Optional
- Entity 필드, DTO 필드, 메서드 파라미터, 컬렉션 타입에는 `Optional`을 사용하지 않는다.
- 결과가 없을 수 있는 단건 조회의 반환 타입에는 `Optional<T>`를 사용할 수 있다.

## Service 메서드명
- 조회 `find`, 생성 `create`, 수정 `update`, 삭제 `delete`
- Boolean 반환은 `is` / `has` / `can`, 개수 조회는 `count`, 합계 조회는 `sum`을 사용한다.
- 조회 기준은 `By`, 추가 업무 조건은 필요할 때 `With` 등으로 명시한다.
