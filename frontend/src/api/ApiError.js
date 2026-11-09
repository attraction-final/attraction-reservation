// 서버가 실패 응답(4xx, 5xx)을 줬을 때 쓰는 에러 객체
// status: HTTP 상태 코드 (예: 401 = 로그인 필요, 409 = 이미 처리됨)
// code: 서버 ErrorCode 이름 (예: 'ATTRACTION_NOT_FOUND'). 같은 status 안에서 원인을 구분할 때 써요.
//       message 는 문장이라 바뀔 수 있으니 분기에는 code 를 써 주세요. 서버 응답이 아니면 null 이에요.
export class ApiError extends Error {
  constructor(message, status, code = null) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
