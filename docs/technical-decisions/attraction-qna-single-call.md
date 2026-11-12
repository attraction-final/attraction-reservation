# 유료 AI 호출 줄이기: 한 번의 호출로 두 가지 분류, 검증은 호출 전에

> 분류: 성능 · 비용 · 관련 이슈: KAN-119, KAN-121 · 기준 코드: `dev` `JevAttractionQuestionClassifier`, `AttractionQuestionController`

| 항목 | 내용 |
| --- | --- |
| 문제 | Jev는 입력 토큰으로 과금된다. 어트랙션과 의도를 따로 물으면 질문과 목록을 두 번 보내 비용과 지연이 두 배가 된다 |
| 결정 | `systemOne` 한 번의 요청에 Choice 두 개를 담는다. 입력 검증 · 인증은 Jev 호출 전에 끝낸다 |
| 효과 | 질문 1건당 호출 1번으로 고정. 잘못된 요청이나 미로그인 요청은 유료 API를 부르지 않는다 |

## 1. 문제

어트랙션 분류와 의도 분류를 따로 호출하면 질문과 어트랙션 목록을 두 번 보내게 된다. Jev는 입력 토큰으로 과금되므로 비용이 두 배가 되고, 외부 호출 지연도 두 번 생긴다.

## 2. 결정

- `systemOne` 한 번의 요청에 `attraction`, `intent` 두 Choice를 함께 담는다.
- 다음 요청은 Jev를 부르기 전에 막는다.

| 요청 | 응답 | 테스트 (`AttractionQuestionControllerTest`) |
| --- | --- | --- |
| 비었거나 공백만 있는 질문 | 400 | `createAttractionQuestion_blank_returnsBadRequest` (시나리오 11) |
| 200자 초과 질문 | 400 (정확히 200자는 통과) | `createAttractionQuestion_tooLong_returnsBadRequest`, `createAttractionQuestion_maxLength_passes` (시나리오 12) |
| 로그인하지 않은 요청 | 401 | `createAttractionQuestion_unauthenticated_returnsUnauthorized` (시나리오 13) |

- 과금 기준인 입력 토큰(`inputTokens`)을 요청마다 로그에 남긴다. Jev가 알려 주지 않으면 0이 아니라 `null`로 남긴다 (`JevAttractionQuestionClassifierTest#classify_logsInputTokens`).

## 3. 근거

- 호출 수가 질문당 1번으로 고정된다 (`JevAttractionQuestionClassifierTest#classify_sendsQuestionAndOptions`: 질문은 한 번, Choice는 두 개로 보낸다).
- 입력 토큰을 로그로 남겨 Grafana(Loki)에서 사용량을 추적할 수 있다.

## 4. 한계

- 두 분류를 한 요청에 담았기 때문에, 한쪽만 다시 묻는 것은 불가능하다. 지금 판정 규칙에서는 필요 없다.

## 관련 문서

- [외부 AI 장애 격리](attraction-qna-failure-isolation.md)
- [로그에 질문 원문 남기지 않기](attraction-qna-log-privacy.md)
- [어트랙션 Q&A 기술 결정 목록](attraction-qna-jev.md)
