# Entity와 Repository

## Entity
- `@Table(name = "...")`에 테이블명을 명시한다.
- 테이블명은 복수형을 기본으로 하고 프로젝트 전체에 일관된 규칙을 적용한다.
- Entity를 API 요청·응답으로 직접 노출하지 않는다.

```java
@Entity
@Table(name = "reservations")
public class Reservation {
    // ...
}
```

## Repository
- Spring Data JPA와 프로젝트에서 사용하는 QueryDSL Custom 구현 방식을 따른다.
- Derived Query는 `[동작]By[조회 조건][정렬/조회 방식]` 형태로 작성한다.
- 조회 조건은 실제 Entity 필드명과 일치시킨다.
- 단건 조회 결과가 없을 수 있으면 `Optional<T>`, 여러 건은 `List<T>`를 사용한다.

예시: `findByStatus()`, `findAllByStatusOrderByCreatedAtDesc()`, `countByAttractionId()`, `existsByUserIdAndAttractionId()`
