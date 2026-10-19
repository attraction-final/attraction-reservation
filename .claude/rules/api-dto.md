# Controller, DTO, Validation

## Controller
- API 경로는 `/api/{domain}` 형식을 기본으로 한다.
- `@RequestBody` DTO 변수명은 `request`로 통일한다.
- 메서드명은 조회 `get`, 생성 `create`, 수정 `update`, 삭제 `delete`를 사용한다.
- 단건 조회 기준은 `ById`, 전체 조회는 `All`을 사용하되 기존 프로젝트의 일관된 규칙이 있으면 그것을 따른다.

## DTO
- DTO는 `record`로 작성하고 Setter를 사용하지 않는다.
- 요청 DTO와 응답 DTO를 분리한다.
- Entity를 Controller 요청·응답에 직접 사용하지 않는다.
- 이름은 `[CRUD/Search] + [Domain] + [Request/Response/Condition]` 형태를 사용한다.
- `of()`는 전달받은 값으로 생성할 때, `from()`은 다른 타입을 변환해 생성할 때 사용한다.

## Validation
- 필수 문자열: `@NotBlank`
- 문자열 패턴: `@NotBlank` + `@Pattern`
- 문자열 길이: `@NotBlank` + `@Size`
- 필수 숫자: `@NotNull`
- 숫자 최솟값/최댓값/범위: `@NotNull` + `@Min` / `@Max`
- `@NotBlank`에 `@NotNull`을 중복 적용하지 않는다.
- 요청 DTO 검증이 실행되도록 필요한 곳에 `@Valid`를 사용한다.
- null 검증이 필요하면 `int`/`long` 대신 `Integer`/`Long`을 고려한다.
