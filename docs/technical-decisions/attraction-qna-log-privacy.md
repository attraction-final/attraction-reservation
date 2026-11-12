# 로그에 개인정보 남기지 않기: 질문 원문 대신 길이 · 확신도 · 토큰만

> 분류: 운영 · 보안 · 관련 이슈: KAN-121 · 기준 코드: `dev` `JevAttractionQuestionClassifier#logClassified`, `LogValues`

| 항목 | 내용 |
| --- | --- |
| 문제 | 질문에 아이 이름 · 키 같은 개인정보가 들어온다. 원문이나 외부 응답을 그대로 남기면 로그 저장소(Loki)에 쌓인다 |
| 결정 | 원문 대신 길이 · 확신도 · 의도 · 모델 · 입력 토큰만 남긴다. 예외 객체는 로그로 넘기지 않는다 |
| 근거 | 원문 없이도 기준값 조정과 비용 추적에 필요한 값은 모두 남는다 |

## 1. 문제

"우리 딸 지우 키 105인데 썬더폴스 탈 수 있어?"처럼 질문에 이름과 키가 들어온다. 질문 원문이나 외부 응답을 그대로 로그에 남기면 개인정보가 로그 저장소(Loki)에 쌓인다. Jev가 오류 응답 본문에 질문을 되돌려줄 수도 있다.

## 2. 결정

- 성공 로그(`event=ATTRACTION_QNA_CLASSIFIED`)에는 `durationMs`, `questionLength`, `attractionId`, 확신도, 의도, 모델, `inputTokens`만 남긴다. 질문 원문은 남기지 않는다.
- 실패 로그(`event=ATTRACTION_QNA_JEV_FAILED`)에는 예외 종류만 남긴다. 예외 메시지에 응답 본문(질문 포함)이 들어갈 수 있어 **예외 객체를 로그로 넘기지 않는다.**
- 모델 이름처럼 외부에서 받은 값은 `LogValues.quoted`로 감싸, 값 안에 로그 키를 끼워 넣지 못하게 한다.
- 성공 로그는 응답 해석까지 끝난 뒤에 남긴다. 해석에 실패한 응답이 성공으로 기록되지 않는다.
- `target=jev`로 검색하면 Jev 호출만 모아 볼 수 있다.

## 3. 근거

- 확신도와 입력 토큰이 남아 있어, 원문 없이도 기준값 조정과 비용 추적을 할 수 있다.
- 검증

| 테스트 | 확인 내용 |
| --- | --- |
| `JevAttractionQuestionClassifierTest#classify_doesNotLogQuestion` | 질문 원문은 남기지 않고 길이만 남긴다 (시나리오 16) |
| `JevClientHttpTest#classify_errorBodyEchoesQuestion` | Jev가 오류 응답에 질문을 되돌려줘도 실패 로그에 남지 않는다 (시나리오 16) |
| `JevAttractionQuestionClassifierTest#classify_modelWithQuote_logEscaped` | 모델 이름에 따옴표가 있어도 로그 키를 끼워 넣지 못한다 |
| `JevAttractionQuestionClassifierTest#classify_unexpectedOption_logsExceptionTypeOnly` | 보내지 않은 옵션이 오면 응답 값 없이 예외 종류만 남긴다 |

## 관련 문서

- [로깅 컨벤션](../logging/logging.md)
- [어트랙션 Q&A 기술 결정 목록](attraction-qna-jev.md)
