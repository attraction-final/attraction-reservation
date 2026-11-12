# AI 응답을 어트랙션 ID로만 되돌리기: 선택지 이름은 ID, 보내지 않은 값은 실패

> 분류: 데이터 정합성 · 관련 이슈: KAN-119 · 기준 코드: `dev` `JevAttractionQuestionClassifier`

| 항목 | 내용 |
| --- | --- |
| 문제 | 사용자는 이름을 줄이거나 띄어쓰기를 바꿔 묻는다. 응답의 이름을 DB 이름과 문자열로 맞추면 쉽게 깨진다 |
| 결정 | 선택지 이름은 어트랙션 ID, 설명은 어트랙션 이름으로 보낸다. 보내지 않은 값 · 빈 응답 · 해석 불가 응답은 503 |
| 근거 | ID는 바뀌지 않아 응답을 항상 같은 어트랙션으로 되돌린다. 추측해서 답하는 것보다 답하지 않는 편이 낫다 |

## 1. 문제

사용자는 "모니모러쉬", "모니모", "렛츠트위스트"처럼 줄이거나 띄어쓰기를 바꿔 묻는다. 응답으로 받은 어트랙션 이름을 DB 이름과 문자열로 맞추면, 이름 변경 · 공백 · 특수문자에 쉽게 깨진다. 외부 AI가 보낸 적 없는 값을 돌려줄 가능성도 막아야 한다.

## 2. 결정

- 선택지의 **이름은 어트랙션 ID**, **설명은 어트랙션 이름**으로 보낸다. Jev는 둘 다 보고 고른다.
- 응답 값은 보낸 ID 목록에서만 찾는다. 보내지 않은 값, 답이 빠진 응답, JSON이 아닌 응답은 `IllegalArgumentException`으로 처리해 **추측하지 않고 503**으로 끝낸다 (시나리오 20).
- 선택지는 삭제되지 않은 어트랙션만 보낸다. 삭제한 어트랙션의 정보를 답할 수 없다 (시나리오 19).
- 지시문은 영어로 쓰고, 어트랙션 이름과 질문은 한국어 그대로 보낸다. Jev 문서에 한국어 지시문의 정확도가 영어보다 낮다고 되어 있기 때문이다.

## 3. 근거

- ID는 바뀌지 않으므로 응답을 항상 같은 어트랙션으로 되돌릴 수 있다. 시나리오 1에서 띄어쓰기가 다른 "모니모러쉬"를 "모니모 러쉬"로 인식한다.
- 검증

| 테스트 | 확인 내용 |
| --- | --- |
| `JevAttractionQuestionClassifierTest#classify_unexpectedOption` | 보내지 않은 옵션이 돌아오면 추측하지 않고 503 |
| `JevAttractionQuestionClassifierTest#classify_missingAnswer` | 응답에 답이 빠져 있으면 503 |
| `JevClientHttpTest#classify_invalidBody` | 200이어도 해석할 수 없는 응답(답 없음, 형식 다름, 선택지 없음)이면 503 |
| `AttractionQuestionServiceTest#classify_receivesQuestionAndAttractions` | 분류기에는 삭제되지 않은 어트랙션 목록만 넘긴다 |

## 관련 문서

- [외부 AI 장애 격리](attraction-qna-failure-isolation.md)
- [어트랙션 Q&A 기술 결정 목록](attraction-qna-jev.md)
