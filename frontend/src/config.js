// 앱 전체에서 쓰는 설정값을 한곳에 모아 둔 파일입니다.

// 서비스 이름: 여기 한 줄만 바꾸면 홈·관리자 화면의 이름이 모두 바뀝니다.
export const SERVICE_NAME = '(서비스 이름)';

// 가짜 데이터 사용 여부 (.env.development / .env.production 파일에서 정해요)
export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true';

// 스프링부트 API 주소의 앞부분. 모든 요청은 /api/... 로 갑니다.
export const API_BASE = '/api';
