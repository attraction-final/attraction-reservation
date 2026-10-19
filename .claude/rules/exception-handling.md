# 예외 처리

- 전역 예외는 `GlobalExceptionHandler`에서 처리하고 `@RestControllerAdvice`, `@ExceptionHandler`를 사용한다.
- 비즈니스 예외는 사용자 정의 예외로 표현한다.
- 오류 코드, HTTP 상태, 메시지는 `ErrorCode`에서 관리한다.
- Service는 상황에 맞는 예외를 발생시키고 HTTP 응답 변환은 Handler에 위임한다.
- Controller에서 반복적인 `try-catch`를 사용하지 않는다.
- 입력값 검증 오류와 예상하지 못한 서버 오류도 공통 처리한다.
- SQL, 내부 경로, 스택 트레이스 등 민감한 정보를 오류 응답에 노출하지 않는다.

## HTTP 상태 코드
- `400`: 잘못된 요청 또는 입력값
- `401`: 인증되지 않은 사용자
- `403`: 권한 없음
- `404`: 데이터를 찾을 수 없음
- `409`: 현재 상태와 충돌하는 요청
- `500`: 예상하지 못한 서버 오류

구현 예시는 `docs/examples/error-handling-and-api-response.md`를 참고한다.
프로젝트에서 `BusinessException` 또는 `CustomException` 중 어떤 이름을 사용하는지 확인하고 통일한다.
