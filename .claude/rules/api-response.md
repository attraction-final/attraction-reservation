# 공통 API 응답

- 일반적인 API는 `ApiResponse` 공통 응답 형식을 사용한다.
- 성공 여부는 `success`, 메시지는 `message`, 응답 데이터는 `data`에 담는다.
- 데이터가 없는 성공 응답은 `data: null`로 반환한다.
- 성공과 실패 응답 모두 동일한 구조를 사용한다.
- HTTP 상태 코드와 `success` 값이 일치하도록 한다.
- 검증 오류와 서버 오류에도 공통 응답 형식을 적용한다.
- 파일 다운로드·스트리밍처럼 JSON이 적합하지 않은 응답은 예외로 둘 수 있다.

```json
{
  "success": true,
  "message": "예약 조회에 성공했습니다.",
  "data": {
    "reservationId": 1,
    "status": "RESERVED"
  }
}
```

구현 예시는 `docs/examples/error-handling-and-api-response.md`를 참고한다.
