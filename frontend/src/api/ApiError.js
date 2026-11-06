// 서버가 실패 응답(4xx, 5xx)을 줬을 때 쓰는 에러 객체
// status: HTTP 상태 코드 (예: 401 = 로그인 필요, 409 = 이미 처리됨)
export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}
