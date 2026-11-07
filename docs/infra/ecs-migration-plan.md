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
| 스케줄러 | 6개. 운영(ASG)에는 5개, `AttractionSlotGenerationScheduler`(KAN-123, 기동 시 + 매일 00:05)는 dev에만 있다. 모두 `*.scheduler.enabled` 스위치 있음. 스케줄러 스레드 풀 3 |

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
| 헬스체크 | ALB: 8080 `/readyz` | 컨테이너 헬스체크: 8081 liveness |
| 8081 | 메트릭 | 메트릭 |
| 개수 | 1~N | 1 |
| 배포 | 최소 100% / 최대 200%, 서킷 브레이커 | 최소 0% / 최대 100% (이전 worker와 겹치지 않게), 서킷 브레이커 |

## 3. 단계

### 1단계: 애플리케이션 준비

현재 EC2에 배포해도 동작이 바뀌지 않는다(프로필이 `prod`라 스케줄러가 모두 켜진 상태 유지). 전환 일정과 관계없이 먼저 진행할 수 있다.

| 파일 | 변경 |
|---|---|
| `TicketExpirationScheduler.java` | `@ConditionalOnProperty(name = "ticket.expiration.scheduler.enabled", havingValue = "true", matchIfMissing = true)` 추가 |
| `application.yaml` | `ticket.expiration.scheduler.enabled: true` 추가. `spring.task.scheduling.shutdown.await-termination: true`, 대기 시간 20s(`stopTimeout` 30s보다 짧게) 추가 |
| `application-api.yml` (새 파일) | 스케줄러 `enabled: false` (처음 5개, 이후 KAN-123 슬롯 생성 스케줄러 추가로 6개) |
| 스케줄러 프로필 테스트 (새 파일) | `api` 프로필이면 스케줄러 빈이 없고, 기본이면 모두 등록되는지 검증 |

검증
- [x] 새 테스트와 기존 테스트 통과 (`e0068033`, dev 머지)
- 현재 EC2 배포 확인은 하지 않는다. EC2 자동 배포는 main의 CI 성공이 있어야 시작되는데, main에 dev 변경분이 많이 쌓여 있어 1단계만 따로 내보낼 수 없다. 실제 동작은 3단계(api의 스케줄러 OFF)와 4단계(worker의 스케줄러 6개 실행)에서 확인한다.

### 2단계: 사이드카와 task definition

| 파일 | 내용 |
|---|---|
| `deploy/ecs/fluent-bit/` | `aws-for-fluent-bit:3.5.4` + `extra.conf`, `parsers.conf`. Fargate의 FireLens는 사용자 설정을 이미지 안 파일로만 받는다. `app-firelens-*` 태그(app 컨테이너)만 Loki로 보내고, multiline(`^ts=`)으로 스택 트레이스를 묶고, FireLens가 붙이는 컨테이너 정보를 지워 logfmt 한 줄만 보낸다. 레이블 `app`, `env`, `service`(`api`·`worker`), `instance`(태그 `app-firelens-<task ID>`에서 꺼낸 task ID. 메트릭의 `instance`와 같은 값) |
| `deploy/ecs/alloy/` | `grafana/alloy:v1.21.3` + `config.alloy`. 메트릭 전용. 대상 `localhost:8081`. `instance` 레이블은 `remote.http`로 `$ECS_CONTAINER_METADATA_URI_V4/task`를 읽어 TaskARN 마지막 부분(task ID)을 쓴다. `service`(`api`·`worker`)도 붙인다 |
| `deploy/ecs/api-task-definition.json` | arm64, 1 vCPU / 2GB(가안), 프로필 `prod,api`, 포트 8080·8081 |
| `deploy/ecs/worker-task-definition.json` | api와 같고 프로필 `prod`, 포트 없음, 컨테이너 헬스체크 `curl -fs localhost:8081/actuator/health/liveness` (앱 이미지에 curl 있음) |

컨테이너 구성 (두 task definition 공통)

| 컨테이너 | 메모리 상한 | essential | 비고 |
|---|---|---|---|
| `app` | 1400MB | O | `stopTimeout: 30`, `secrets` 10개(`env/*`, `SPRING_AI_TYPESAFE_APIKEY`를 넣으면 11개), 로그 드라이버 `awsfirelens`, `log-router`가 뜬 뒤 시작 |
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
| `<LOKI_HOST>`, `<LOKI_PORT>` | `loki.attraction.internal`, `3100` (Fluent Bit Loki 출력은 URL이 아니라 host·port를 받는다). 모니터링 EC2가 바뀌어도 task definition을 다시 등록하지 않도록 IP 대신 프라이빗 DNS 이름을 쓴다 |
| `<AWS_REGION>` | 사이드카 로그 그룹 리전 |

검증 (2026-11-07 완료)
- [x] 사이드카 이미지 빌드 성공 (로컬, `linux/arm64`)
- [x] task definition 2개가 ECS `RegisterTaskDefinition` 입력 스키마에 맞음 (botocore 검증, 등록은 하지 않음)
- [x] Fluent Bit: fluentd 로그 드라이버(FireLens와 같은 경로) → 로컬 Loki에서 스택 트레이스가 한 건으로 묶이고, app 외 태그는 보내지지 않고, 줄이 logfmt 원문 그대로임
- [x] Alloy: 가짜 메타데이터·actuator로 실행 → Prometheus에 `instance=<task ID>`, `app`, `env` 레이블로 수집됨

### 3단계: AWS 리소스 생성

사전 확인: api task는 dev 최신 이미지로 뜨므로, 사용자 트래픽이 없어도 기동하면서 운영 DB에 Flyway 마이그레이션을 적용한다. 운영 앱(`APP_VERSION`)과 dev 사이의 변경을 먼저 검토한다.
- [x] 적용될 마이그레이션 목록과 운영 DB에 적용할 수 있는지 (2026-11-07 확인)
  - 운영 `flyway_schema_history`: V1 baseline, V2 적용(2026-11-04). 새로 적용되는 것은 V3(`attractions` 탑승 정보 컬럼 5개, NULL 허용) 하나
  - 운영 `attractions`에 V3 컬럼이 없음(`docs/sql/kan-117-…` 1단계를 직접 실행하지 않음) → `Duplicate column name` 실패 없음
  - 운영 DB에 어트랙션·슬롯 데이터가 없음(0건). 초기 데이터(`docs/sql/kan-123-…`) 적용은 이 전환과 별개로 정한다
- [x] 4단계에서 기존 버전(ASG)과 새 버전(ECS)이 함께 트래픽을 받으므로, 기존 버전이 새 스키마에서도 동작하는지: V3가 NULL 허용 컬럼 추가뿐이라 호환됨
- [x] dev에서 추가된 스케줄러가 api 프로필에서 꺼지는지: `AttractionSlotGenerationScheduler`를 `application-api.yml`에 추가하고 `SchedulerProfileTest`로 검증. 끄지 않으면 api task가 뜰 때마다 슬롯 생성이 돌고(`attraction_slots`에 UNIQUE 제약 없음) task 수만큼 중복 실행된다
- [x] 새로 필요한 환경변수가 SSM `env/`에 있는지: `SPRING_AI_TYPESAFE_APIKEY`(Jev 연동)가 없다. 없어도 앱은 뜨고 어트랙션 Q&A만 503을 응답한다(운영에는 아직 Q&A 기능이 없음). 전환과 함께 Q&A를 열려면 SSM 파라미터와 task definition `secrets`에 추가한다

- [x] Q&A(`SPRING_AI_TYPESAFE_APIKEY`)는 전환과 함께 열지 않는다(2026-11-07 결정). `secrets`는 10개 그대로 간다

시작 조건
- 앱 이미지에 슬롯 생성 스케줄러를 끄는 수정(PR #238)이 들어 있다. 리뷰를 기다리지 않고 PR 브랜치 이미지로 진행한다(2026-11-08 결정). 브랜치가 dev 최신 위에 있고 앱 코드 차이는 이 수정뿐이다. 4단계에서 트래픽을 넘기기 전에 머지된 dev SHA 이미지로 바꿔 배포한다
- 앱 이미지가 ECR `attraction-reservation:<커밋 SHA>`에 있다. CD는 main에서만 돌므로 이번에는 로컬에서 arm64로 빌드해 올린다(CD와 같은 태그 규칙). Dockerfile에 `RUN`이 있어 로컬 Docker에 arm64 에뮬레이션(`tonistiigi/binfmt --install arm64`)이 필요하다.

사용하는 기존 리소스
| 항목 | 값 |
|---|---|
| VPC / 서브넷 | `vpc-0283cebcecbda88c5` / private `subnet-0be2496d3931e4dc6`, `subnet-008fc962259300add` (ASG와 같음) |
| task SG | app SG `sg-0dfee6a3408712b64` 재사용 (ALB SG에서 8080·8081 허용, RDS·Redis·Loki·Prometheus 규칙 유지) |
| SSM | `env/` 10개(SecureString 4개는 `alias/aws/ssm`), `LOKI_URL`(`10.0.130.10:3100`), `PROMETHEUS_URL` |

순서
1. ECR 저장소 `attraction-reservation-fluent-bit`, `attraction-reservation-alloy` 생성, 사이드카 이미지를 arm64로 빌드해 push (태그: 커밋 SHA)
2. 사이드카 로그 그룹 `/ecs/attraction-reservation` (보존 7일)
3. IAM (IAM 쓰기 권한이 있는 계정에서 실행)
   - ECS 서비스 연결 역할 `AWSServiceRoleForECS` (계정에 아직 없음)
   - 실행 역할 `attraction-ecs-execution-role`: `AmazonECSTaskExecutionRolePolicy`(ECR pull, 로그) + 인라인 `ssm:GetParameters`(`env/*`, `PROMETHEUS_URL`). `alias/aws/ssm`은 AWS 관리형 키라 `kms:Decrypt`를 따로 주지 않는다
   - task 역할 `attraction-ecs-task-role`: ECS Exec용 `ssmmessages:*`
4. ECS 클러스터 `attraction-reservation` (Fargate)
5. 새 TG `attraction-app-ecs`: ip 유형, HTTP 8080, 헬스체크 **8080 `/readyz`**, 등록 해제 지연 60초. ECS 서비스는 ALB에 연결된 TG만 받으므로, HTTPS 리스너에 헤더 조건 규칙(우선순위 20, `X-Ecs-Preview: <임의 값>` → 이 TG)을 추가한다. 사용자 요청은 이 헤더가 없어 기본 규칙(`attraction-final`)으로 가고, 헤더를 붙인 확인용 요청만 ECS로 간다. 값은 저장소에 남기지 않는다. 4단계에서 기본 규칙을 가중치 forward로 바꿀 때 이 규칙을 지운다
   - dev의 `application-prod.yml`(`probes.add-additional-paths`)은 서비스 포트에 `/readyz`를 열고 로드밸런서가 이를 보게 한다. 관리 포트(8081)의 readiness는 서비스 포트가 요청을 받는지 확인하지 않기 때문이다. 기존 TG `attraction-final`은 이 변경이 운영에 나가지 않아 아직 8081 readiness를 본다
6. Route 53 프라이빗 호스팅 영역 `attraction.internal`(`Z0261876YAS37FHXGKQ4`)을 VPC에 연결하고 A 레코드 `loki.attraction.internal` → `10.0.130.10`(TTL 60초)을 만든다. VPC `enableDnsSupport`·`enableDnsHostnames`가 켜져 있고 DHCP 옵션이 `AmazonProvidedDNS`라 task에서 이름이 풀린다. 모니터링 EC2를 바꾸면 이 레코드만 고친다
7. task definition 2개 등록: 자리 표시자를 위 값으로 채워 등록한다. 저장소 파일은 그대로 두고, 렌더링 자동화는 5단계 CD에서 한다
8. 서비스 생성

| | api | worker |
|---|---|---|
| desired | 1 | **0** (생성만) |
| 네트워크 | private 서브넷 2개, app SG, 퍼블릭 IP 없음 | 같음 |
| 로드밸런서 | `attraction-app-ecs` ← `app`:8080 | 없음 |
| 배포 | 최소 100% / 최대 200%, 서킷 브레이커(rollback) | 최소 0% / 최대 100%, 서킷 브레이커(rollback) |
| 기타 | `healthCheckGracePeriodSeconds` 120 (Flyway 포함 기동 시간), ECS Exec 켬 | ECS Exec 켬 |

이 단계의 운영 영향
- api task가 기동하며 운영 DB에 Flyway V3를 적용한다. NULL 허용 컬럼 추가라 기존 ASG 버전과 호환되고, 되돌리지 않는다.
- api는 스케줄러 OFF, worker는 0개라 스케줄러 쓰기는 없다.
- ALB에는 헤더 조건 규칙만 추가하고 기본 규칙은 바꾸지 않으므로 사용자 트래픽은 들어가지 않는다.
- 비용: Fargate arm64 1 vCPU / 2GB task 1개(하루 약 1달러)가 4단계 완료까지 ASG와 함께 돈다.

검증 (2026-11-08, task `6cc6c2f5…`, 이미지 `b9682e47`)
- [x] api task가 RUNNING이고 `attraction-app-ecs`에서 healthy
- [x] Loki `{app="attraction-reservation", service="api"}`에서 logfmt 원문 조회, `instance`가 task ID. 기동 로그 54줄이 모두 `ts=`로 시작하고 여러 줄 로그가 한 건으로 묶임 (스택 트레이스 묶음은 2단계 로컬 검증으로 대신함)
- [x] Prometheus에 `instance=<task ID>`, `service="api"` 메트릭 수집 (`up`=1)
- [x] api task에 스케줄러 실행 메트릭이 없음 (`api` 프로필 적용). 같은 조회(`tasks_scheduled.*`)가 기존 EC2에서는 30개를 돌려줌
- [x] Flyway V3 성공: 앱 로그 `Successfully applied 1 migration … now at version v3` (0.243초)
- [x] DB 직접 확인: `flyway_schema_history` V3 `success=1`(2026-11-07 19:06:06 UTC, 243ms), `attractions`에 V3 컬럼 5개(NULL 허용) 추가, 어트랙션·슬롯 0건 그대로
- [x] 사이드카 로그가 CloudWatch `/ecs/attraction-reservation`에 남고 오류가 없음
- [x] task 안에서 `loki.attraction.internal`이 `10.0.130.10`으로 풀림 (ECS Exec). Route 53 `TestDNSAnswer`는 프라이빗 영역을 지원하지 않아 VPC 안에서 확인한다
- [x] ECS Exec로 app 컨테이너 접속
- [x] ALB 기본 규칙과 Grafana 규칙이 바뀌지 않음 (사용자 트래픽 없음), 헤더를 붙인 요청은 ALB를 거쳐 ECS에서 응답

롤백
- 서비스를 desired 0으로 내리거나 삭제한다. 클러스터·TG·역할은 남겨도 사용자 트래픽에 영향이 없다.

### 4단계: 트래픽 전환

1. ALB 443 기본 동작을 가중치 forward로 변경: 기존 TG 100 / 새 TG 0
2. 10 → 50 → 100 순으로 이동. 단계마다 Loki 오류 로그, Grafana 5xx·지연 시간 확인
3. 100% 이후 worker desired 1, 곧바로 ASG desired 0
   - 전환 중 스케줄러는 ASG 인스턴스에서만 실행된다(ECS api는 스케줄러 OFF).
   - 마지막에 잠깐 겹쳐도 멱등 처리(행 잠금, 상태 재확인, 조건부 UPDATE)로 안전하다.

검증
- [ ] 각 가중치 단계에서 5xx 비율과 지연 시간이 전환 전과 같은 수준
- [ ] 로그인(쿠키, CSRF), 결제 승인, 웹훅 수신 정상
- [ ] worker로 넘긴 뒤 스케줄러 대시보드에서 worker의 `instance`로만 6개 실행 확인 (슬롯 생성은 worker 기동 시와 매일 00:05)

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
- 모니터링 주소를 프라이빗 DNS 이름으로 통일: EC2 Alloy의 Loki 주소, SSM `LOKI_URL`·`PROMETHEUS_URL`(EC2와 함께 쓰는 값이라 이번에 바꾸지 않음. Prometheus 레코드도 그때 만든다)
