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
- [x] 새 테스트와 기존 테스트 통과 (`e0068033`, dev 머지)
- 현재 EC2 배포 확인은 하지 않는다. EC2 자동 배포는 main의 CI 성공이 있어야 시작되는데, main에 dev 변경분이 많이 쌓여 있어 1단계만 따로 내보낼 수 없다. 실제 동작은 3단계(api의 스케줄러 OFF)와 4단계(worker의 스케줄러 5개 실행)에서 확인한다.

### 2단계: 사이드카와 task definition

| 파일 | 내용 |
|---|---|
| `deploy/ecs/fluent-bit/` | `aws-for-fluent-bit:3.5.4` + `extra.conf`, `parsers.conf`. Fargate의 FireLens는 사용자 설정을 이미지 안 파일로만 받는다. `app-firelens-*` 태그(app 컨테이너)만 Loki로 보내고, multiline(`^ts=`)으로 스택 트레이스를 묶고, FireLens가 붙이는 컨테이너 정보를 지워 logfmt 한 줄만 보낸다. 레이블 `app`, `env`, `service`(`api`·`worker`) |
| `deploy/ecs/alloy/` | `grafana/alloy:v1.21.3` + `config.alloy`. 메트릭 전용. 대상 `localhost:8081`. `instance` 레이블은 `remote.http`로 `$ECS_CONTAINER_METADATA_URI_V4/task`를 읽어 TaskARN 마지막 부분(task ID)을 쓴다. `service`(`api`·`worker`)도 붙인다 |
| `deploy/ecs/api-task-definition.json` | arm64, 1 vCPU / 2GB(가안), 프로필 `prod,api`, 포트 8080·8081 |
| `deploy/ecs/worker-task-definition.json` | api와 같고 프로필 `prod`, 포트 없음, 컨테이너 헬스체크 `curl -fs localhost:8081/actuator/health/liveness` (앱 이미지에 curl 있음) |

컨테이너 구성 (두 task definition 공통)

| 컨테이너 | 메모리 상한 | essential | 비고 |
|---|---|---|---|
| `app` | 1400MB | O | `stopTimeout: 30`, `secrets` 10개(`env/*`), 로그 드라이버 `awsfirelens`, `log-router`가 뜬 뒤 시작 |
| `log-router` | 128MB | O | 멈추면 앱 로그가 사라지므로 task를 함께 내린다. 종료는 app보다 늦다(의존 관계 역순) |
| `alloy` | 256MB | X | 메트릭이 끊겨도 서비스는 유지한다. 6단계 알림(`up` 없음)으로 감지 |

- 사이드카 자신의 로그는 CloudWatch Logs `/ecs/attraction-reservation`으로 보낸다. 로그 경로(Fluent Bit)가 고장 나면 Loki로는 원인을 볼 수 없기 때문이다. 앱 로그는 보내지 않는다.
- stdout에는 logfmt만 나가야 한다는 규칙은 그대로 적용된다(`JAVA_TOOL_OPTIONS` 미사용 유지).
- 환경마다 다른 값은 자리 표시자로 두고 5단계 CD가 채워 등록한다.

| 자리 표시자 | 값 |
|---|---|
| `<APP_IMAGE>`, `<FLUENT_BIT_IMAGE>`, `<ALLOY_IMAGE>` | ECR 이미지 주소:태그 |
| `<EXECUTION_ROLE_ARN>`, `<TASK_ROLE_ARN>` | 3단계에서 만드는 역할 |
| `<SSM_PARAMETER_ARN_PREFIX>` | `arn:aws:ssm:<리전>:<계정>:parameter/attraction-reservation/prod` |
| `<LOKI_HOST>`, `<LOKI_PORT>` | SSM `LOKI_URL`에서 나눈 값 (Fluent Bit Loki 출력은 URL이 아니라 host·port를 받는다) |
| `<AWS_REGION>` | 사이드카 로그 그룹 리전 |

검증 (2026-11-07 완료)
- [x] 사이드카 이미지 빌드 성공 (로컬, `linux/arm64`)
- [x] task definition 2개가 ECS `RegisterTaskDefinition` 입력 스키마에 맞음 (botocore 검증, 등록은 하지 않음)
- [x] Fluent Bit: fluentd 로그 드라이버(FireLens와 같은 경로) → 로컬 Loki에서 스택 트레이스가 한 건으로 묶이고, app 외 태그는 보내지지 않고, 줄이 logfmt 원문 그대로임
- [x] Alloy: 가짜 메타데이터·actuator로 실행 → Prometheus에 `instance=<task ID>`, `app`, `env` 레이블로 수집됨

### 3단계: AWS 리소스 생성

사전 확인: api task는 dev 최신 이미지로 뜨므로, 사용자 트래픽이 없어도 기동하면서 운영 DB에 Flyway 마이그레이션을 적용한다. 운영 앱(`APP_VERSION`)과 dev 사이의 변경을 먼저 검토한다.
- [ ] 적용될 마이그레이션 목록과 운영 DB에 적용할 수 있는지
- [ ] 4단계에서 기존 버전(ASG)과 새 버전(ECS)이 함께 트래픽을 받으므로, 기존 버전이 새 스키마에서도 동작하는지 (컬럼 삭제·이름 변경 등 호환되지 않는 변경이 있는지)
- [ ] 새로 필요한 환경변수가 SSM `env/`에 있는지

1. ECS 클러스터 `attraction-reservation` (Fargate), 사이드카 로그 그룹 `/ecs/attraction-reservation` (보존 7일)
2. IAM
   - Task 실행 역할: ECR pull, SSM `env/*`·`PROMETHEUS_URL` 읽기와 KMS 복호화, 사이드카 로그용 `logs:CreateLogStream`·`logs:PutLogEvents`
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
- [ ] api task에 스케줄러 실행 메트릭이 없음 (`api` 프로필 적용)

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
