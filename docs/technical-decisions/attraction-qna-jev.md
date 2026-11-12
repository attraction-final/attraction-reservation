# 어트랙션 Q&A (Jev AI) 기술 결정 목록

> 기준 코드: `dev` · 동작 확인: [attraction-qna-scenarios.md](../requirements/attraction-qna-scenarios.md) 시나리오 1 ~ 20

자연어 질문을 Jev로 분류하되, 답의 숫자는 DB에서만 나오게 했다. 주제마다 문서를 나눴다.

| 분류 | 주제 | 한 줄 요약 | 문서 |
| --- | --- | --- | --- |
| 기술적 도전 | AI는 분류만, 숫자는 DB에서 | Jev는 어트랙션과 의도만 고르고, 답 문장은 DB 값으로 조립한다 | [attraction-qna-classify-only.md](attraction-qna-classify-only.md) |
| 기술적 도전 | 확신도 0.6 기준 판정 | 미지원 → 되묻기 → 요약 → 답변 순서. 어트랙션이 불확실하면 숫자를 답하지 않는다 | [attraction-qna-confidence-threshold.md](attraction-qna-confidence-threshold.md) |
| 트러블슈팅 | 되묻기 후보 기대값과 실제 응답 불일치 | "롤러코스터"는 실제로 `NONE` 0.94라 후보가 0개. 코드가 맞고 문서 기대값이 틀렸다 | [attraction-qna-clarification-candidates.md](../troubleshooting/attraction-qna-clarification-candidates.md) |
| 데이터 정합성 | 응답을 ID로만 되돌리기 | 선택지 이름은 ID, 보내지 않은 값 · 해석 불가 응답은 503 | [attraction-qna-id-options.md](attraction-qna-id-options.md) |
| 성능 · 비용 | 호출 1번, 검증은 호출 전에 | Choice 두 개를 한 요청에. 빈 질문 · 200자 초과 · 미로그인은 AI를 부르지 않는다 | [attraction-qna-single-call.md](attraction-qna-single-call.md) |
| 성능 · 장애 격리 | 외부 AI 장애 격리 | 키 없어도 기동, 2초 · 재시도 1번 · 전체 5초, 트랜잭션 밖 호출 | [attraction-qna-failure-isolation.md](attraction-qna-failure-isolation.md) |
| 운영 · 보안 | 로그에 개인정보 남기지 않기 | 원문 대신 길이 · 확신도 · 토큰만, 외부 값은 로그 키 주입 차단 | [attraction-qna-log-privacy.md](attraction-qna-log-privacy.md) |
