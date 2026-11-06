# ECS Fargate 전환 계획

애플리케이션 실행 환경을 EC2(ASG)에서 ECS Fargate로 옮기는 단계별 계획이다.
전환 사유와 선택지 비교는 [ecs-migration-rationale.md](ecs-migration-rationale.md)를 참고한다.

- 작성 기준: 2026-11-06
- 단계마다 변경 내용을 확인하고 승인한 뒤 진행한다.
- 동시성·외부 연동이 걸린 단계는 단위 테스트 통과만으로 완료 처리하지 않고, 아래 검증 항목을 실제 환경에서 확인한다.

## 1. 현재 상태

| 항목 | 값 |
|---|---|
| 앱 | ASG `attraction-reservation-app` (desired/min/max 1/1/2), t4g.small, private 서브넷 2개, app SG |
| 앱 TG | `attraction-final`: **instance 유형**, 8080, 헬스체크 8081 `/actuator/health/readiness`, 등록 해제 지연 60초 |
| ALB | `attraction-final`: 443(HTTPS, `*.xevbn.click`), 80은 443으로 리다이렉트, Grafana 호스트 규칙(Cognito 인증) |
| 외부로 나가는 경로 | NAT 게이트웨이, S3 게이트웨이 엔드포인트 |
| 앱 환경변수 | SSM `/attraction-reservation/prod/env/` 아래 10개 (`JWT_SECRET`, `PORTONE_API_SECRET`, `PORTONE_CHANNEL_KEY`, `PORTONE_STORE_ID`, `PORTONE_WEBHOOK_SECRET`, `REDIS_HOST`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_SSL_ENABLED`) |
| 로그·메트릭 | Alloy가 docker.sock으로 app stdout을 읽어 Loki로, `app:8081`을 스크랩해 Prometheus로 보냄 |
| 모니터링 | 모니터링 EC2(`10.0.130.10`)에서 Prometheus·Loki·Grafana. **이번 전환 범위 밖** |
| 스케줄러 | 5개. 4개는 `*.scheduler.enabled` 스위치 있음, `TicketExpirationScheduler`는 없음. 스케줄러 스레드 풀 3 |

## 2. 목표 구성

```
ALB ─443─▶ TG(ip) ─▶ ECS 서비스 api     task 1~N개, 프로필 prod,api → 스케줄러 OFF
                     ECS 서비스 worker  task 1개, ALB 연결 없음, 프로필 prod → 스케줄러 ON

각 task = app 컨테이너 + Fluent Bit(FireLens, 로그 → Loki) + Alloy(메트릭 → Prometheus)
모니터링 EC2, Grafana(ALB + Cognito)는 그대로
```

| 구분 | api | worker |
|---|---|---|
| 이미지 | 같은 앱 이미지 | 같은 앱 이미지 |
| 프로필 | `prod,api` | `prod` |
| ALB | 새 TG(ip)에 연결 | 연결하지 않음 |
| 8080 인바운드 | ALB에서만 | 열지 않음 |
| 8081 | ALB 헬스체크, 메트릭 | 컨테이너 헬스체크, 메트릭 |
| 개수 | 1~N | 1 |
| 배포 | 최소 100% / 최대 200%, 서킷 브레이커 | 최소 0% / 최대 100% (이전 worker와 겹치지 않게), 서킷 브레이커 |

## 3. 단계

### 1단계: 애플리케이션 준비

현재 EC2에 배포해도 동작이 바뀌지 않는다(프로필이 `prod`라 스케줄러가 모두 켜진 상태 유지). 전환 일정과 관계없이 먼저 진행할 수 있다.

| 파일 | 변경 |
|---|---|
| `TicketExpirationScheduler.java` | `@ConditionalOnProperty(name = "ticket.expiration.scheduler.enabled", havingValue = "true", matchIfMissing = true)` 추가 |
| `application.yaml` | `ticket.expiration.scheduler.enabled: true` 추가. `spring.task.scheduling.shutdown.await-termination: true`, 대기 시간 20s(`stopTimeout` 30s보다 짧게) 추가 |
| `application-api.yml` (새 파일) | 스케줄러 5개 `enabled: false` |
| 스케줄러 프로필 테스트 (새 파일) | `api` 프로필이면 스케줄러 빈이 없고, 기본이면 5개가 모두 등록되는지 검증 |

검증
- [ ] 새 테스트와 기존 테스트 통과
- [ ] 현재 EC2에 배포 후 스케줄러 대시보드에서 5개 모두 실행 확인

### 2단계: 사이드카와 task definition

| 파일 | 내용 |
|---|---|
| `deploy/ecs/fluent-bit/` (새 디렉터리) | Dockerfile, 설정, 파서. Fargate의 FireLens는 사용자 설정을 이미지 안 파일로만 받으므로 작은 이미지를 만든다. multiline(`^ts=`), Loki 출력, 레이블 `app`, `env` |
| `deploy/ecs/alloy/` (새 디렉터리) | Dockerfile, `config.alloy`. 메트릭 전용. 대상 `localhost:8081`, `instance` 레이블은 ECS 메타데이터(`ECS_CONTAINER_METADATA_URI_V4`)의 task ID. 주입 방식은 구현할 때 검증한다 |
| `deploy/ecs/api-task-definition.json` (새 파일) | arm64, 크기 가안 1 vCPU / 2GB, `stopTimeout: 30`, `secrets` 10개, 앱 컨테이너 메모리 상한, 포트 8080·8081 |
| `deploy/ecs/worker-task-definition.json` (새 파일) | api와 같고 프로필만 `prod`, 컨테이너 헬스체크(`/actuator/health/liveness`). 이미지에 확인 도구(curl 등)가 있는지 점검 |

- task 크기는 사이드카 메모리까지 합쳐 정한다. JVM은 `MaxRAMPercentage=75`이므로 앱 컨테이너에 메모리 상한을 따로 둔다.
- stdout에는 logfmt만 나가야 한다는 규칙은 그대로 적용된다(`JAVA_TOOL_OPTIONS` 미사용 유지).

검증
- [ ] 로컬에서 사이드카 이미지 빌드 성공
- [ ] Fluent Bit 설정으로 스택 트레이스가 한 로그로 묶이는지 확인

### 3단계: AWS 리소스 생성

1. ECS 클러스터 `attraction-reservation` (Fargate)
2. IAM
   - Task 실행 역할: ECR pull, SSM `env/*` 읽기와 KMS 복호화
   - Task 역할: ECS Exec용 `ssmmessages:*`
3. 새 TG `attraction-app-ecs`: ip 유형, HTTP 8080, 헬스체크 8081 readiness, 등록 해제 지연 60초
4. task SG: 기존 app SG 재사용 (RDS 3306, Redis 6379, Loki 3100, Prometheus 9090, ALB → 8080·8081 규칙 유지)
5. 사이드카 이미지 2개를 ECR에 push
6. 서비스 생성
   - `api`: desired 1, 새 TG 연결, 서킷 브레이커(rollback) ON, `healthCheckGracePeriodSeconds` 설정
   - `worker`: **desired 0으로 생성만**

검증
- [ ] api task가 RUNNING이고 새 TG에서 healthy
- [ ] Loki에서 해당 task의 logfmt 로그 조회, 스택 트레이스가 한 건으로 묶임
- [ ] Prometheus에 task ID를 `instance`로 단 메트릭 수집
- [ ] 이 시점에는 사용자 트래픽이 들어가지 않음

### 4단계: 트래픽 전환

1. ALB 443 기본 동작을 가중치 forward로 변경: 기존 TG 100 / 새 TG 0
2. 10 → 50 → 100 순으로 이동. 단계마다 Loki 오류 로그, Grafana 5xx·지연 시간 확인
3. 100% 이후 worker desired 1, 곧바로 ASG desired 0
   - 전환 중 스케줄러는 ASG 인스턴스에서만 실행된다(ECS api는 스케줄러 OFF).
   - 마지막에 잠깐 겹쳐도 멱등 처리(행 잠금, 상태 재확인, 조건부 UPDATE)로 안전하다.

검증
- [ ] 각 가중치 단계에서 5xx 비율과 지연 시간이 전환 전과 같은 수준
- [ ] 로그인(쿠키, CSRF), 결제 승인, 웹훅 수신 정상
- [ ] worker로 넘긴 뒤 스케줄러 대시보드에서 worker의 `instance`로만 5개 실행 확인

롤백
- 가중치를 기존 TG 100으로 되돌린다.
- worker로 넘긴 뒤라면 ASG desired 1, worker desired 0.

### 5단계: CD 교체 (`cd.yml`)

- `deploy-app`
  - 앱 이미지 빌드는 그대로 유지
  - task definition 2개를 렌더링·등록 → api 서비스 갱신 → 안정화 대기 → worker 서비스 갱신
  - Instance Refresh, 직접 만든 롤백 단계 제거 (서킷 브레이커가 대신함)
  - `workflow_dispatch` 롤백: 지정한 SHA로 task definition을 만들어 갱신
  - 변경 감지용 `APP_VERSION` 파라미터는 계속 갱신
- `deploy/ecs/**`가 바뀌면 사이드카 이미지 재빌드
- 배포 역할 권한: `ecs:RegisterTaskDefinition`, `ecs:UpdateService`, `ecs:DescribeServices`, `ecs:DescribeTaskDefinition`, 두 역할에 대한 `iam:PassRole` 추가. `autoscaling` 권한 제거
- `deploy-monitoring`은 변경 없음

검증
- [ ] 정상 커밋 배포가 api → worker 순으로 끝나고, 배포 시간 측정
- [ ] 일부러 readiness가 실패하는 버전을 배포해 서킷 브레이커 자동 롤백 확인
- [ ] `workflow_dispatch`로 이전 SHA 롤백 확인

### 6단계: 알림 (Grafana Alerting)

- worker의 스케줄러 실행 횟수 5분간 0 (`tasks_scheduled_execution_seconds_count`)
- api 5xx 비율 상승, ALB 정상 대상 0
- (가능하면) 미처리 웹훅·환불 적체량

### 7단계: 정리

- 삭제: ASG, Launch Template, 기존 TG `attraction-final`, `deploy/compose.yml`, `deploy/user-data.sh`, `deploy/alloy/config.alloy`
- 문서 갱신
  - 로깅 규칙 10장(`.claude/rules/logging.md`, `docs/logging/logging.md`): Alloy·docker.sock·json-file 설명을 FireLens·Alloy 사이드카 기준으로
  - `cd.yml` 상단 주석
  - [ecs-migration-rationale.md](ecs-migration-rationale.md)의 상태를 "채택"으로 바꾸고 9장 결과 측정 표 채우기

## 4. 결정할 것

| 항목 | 권장 | 비고 |
|---|---|---|
| 로그 경로 | FireLens → Loki | 대안: CloudWatch Logs (대시보드·`traceId` 링크 재작성, 비용 증가) |
| ShedLock | 이번에는 넣지 않음 | worker 1개 + 배포 시 겹치지 않음 + 멱등 처리. worker를 늘릴 때 검토 |
| task 크기 | api·worker 모두 실측 후 확정 | 가안 1 vCPU / 2GB |
| api task 수 | 전환 후 2개 이상 (여러 AZ) | 단일 장애점 제거. 전환과 별개로 필요 |

## 5. 범위 밖

- 모니터링 스택을 ECS나 관리형(AMP, Amazon Managed Grafana)으로 이전
- ElastiCache 복제본, RDS Multi-AZ
- `TicketExpirationScheduler`의 `SKIP LOCKED` 적용과 로그 형식 정리 (worker를 여러 개로 늘릴 때 함께 진행)
