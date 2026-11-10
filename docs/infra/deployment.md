# 운영 배포 구성

운영 환경이 어떻게 구성되어 있고, 코드가 어떤 경로로 배포되는지 정리한 문서다.
EC2(ASG)에서 ECS Fargate로 옮긴 과정과 이유는 [ecs-migration-plan.md](ecs-migration-plan.md), [ecs-migration-rationale.md](ecs-migration-rationale.md)를 참고한다.

- 기준: 2026-11-10 AWS·GitHub 조회 결과 (계정 `793168138824`, 리전 `ap-northeast-2`)
- 현재 단계: ECS 전환 5단계(트래픽 전환)와 6단계(알림)를 확인했다. 사용자 트래픽과 스케줄러는 모두 ECS에서 처리하고, EC2 앱 인스턴스는 0대다. 새 CD의 첫 main 실행(4단계)과 정리(7단계)가 남았다. 남은 작업은 [8장](#8-남은-작업)에 정리했다.

## 1. 전체 구성

```
                       Route 53 (*.xevbn.click)
                                │
                 ALB attraction-final  (SG attraction-final: 80·443 ← 0.0.0.0/0)
                 ├─ :80   → 443으로 리다이렉트
                 └─ :443  (ACM *.xevbn.click)
                     ├─ 우선순위 10  Host grafana.xevbn.click → Cognito 인증 → TG attraction-grafana → 모니터링 EC2:3000
                     └─ 기본 규칙                                   → TG attraction-app-ecs (ip, 8080, /readyz)
                                                                            │
  ┌─────────────── private 서브넷 2개 (subnet-0be2496d3931e4dc6, subnet-008fc962259300add) ───────────────┐
  │  ECS 클러스터 attraction-reservation (Fargate, ARM64)                                                  │
  │    서비스 api     task 1개  [app | log-router(Fluent Bit) | alloy]  ← ALB                              │
  │    서비스 worker  task 1개  [app | log-router(Fluent Bit) | alloy]  ALB 연결 없음, 스케줄러 실행       │
  │                                                                                                       │
  │  RDS MySQL 8.4 attraction-final-db (db.t4g.micro, 단일 AZ)                                             │
  │  ElastiCache Redis attraction-final-cache (cache.t4g.micro, TLS)                                      │
  │                                                                                                       │
  │  모니터링 EC2 i-0b91df092531d5b72 (t4g.medium, 10.0.130.10)  Prometheus · Loki · Grafana               │
  │    ← Fluent Bit 로그 (loki.attraction.internal:3100), Alloy 메트릭 (remote_write :9090)                │
  └───────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                │
              NAT nat-0f57ee41a21afe804 (public 2a 서브넷, 52.79.121.87, 단일 AZ) → 외부 (PortOne 등)
```

| 구분 | 리소스 |
|---|---|
| VPC | `vpc-0283cebcecbda88c5` |
| 외부로 나가는 경로 | NAT 게이트웨이 1개(2a). private 라우팅 테이블 2개가 모두 이 NAT를 쓴다. 외부 서비스에 이 IP를 허용 목록으로 등록한 곳은 없다 |
| 내부 DNS | Route 53 프라이빗 호스팅 영역 `attraction.internal`(`Z0261876YAS37FHXGKQ4`), `loki.attraction.internal` → `10.0.130.10` (TTL 60초) |
| 인증서 | ACM `*.xevbn.click` (만료 2027-02-14, 자동 갱신 대상) |

### 보안 그룹

| SG | 인바운드 | 쓰는 곳 |
|---|---|---|
| `sg-036d52199ed4bb0d1` `attraction-final` | 80, 443 ← 0.0.0.0/0 | ALB |
| `sg-0dfee6a3408712b64` `attraction-final-app-sg` | 8080, 8081 ← ALB SG | ECS task (api, worker). 이름은 EC2 시절 그대로다 |
| `sg-095178c6c0b041649` `attraction-final-monitoring-sg` | 3100, 9090 ← app SG / 3000 ← ALB SG | 모니터링 EC2 |
| `sg-0bb8a18cbb7fbbfa4` `attraction-final-rds-sg` | 3306 ← app SG | RDS |
| `sg-046a2df87baa920e4` `attraction-final-redis-sg` | 6379 ← app SG | ElastiCache |

## 2. 애플리케이션 실행 환경 (ECS)

같은 이미지를 두 서비스로 나눠 실행한다. 스케줄러가 여러 task에서 겹쳐 돌지 않도록 api는 스케줄러를 끄고, worker 1개만 스케줄러를 실행한다.

| 구분 | api | worker |
|---|---|---|
| task definition | `attraction-reservation-api` | `attraction-reservation-worker` |
| Spring 프로필 | `prod,api` (`application-api.yml`이 스케줄러를 모두 끈다) | `prod` (스케줄러 켬) |
| task 수 | 1 | 1 |
| ALB | TG `attraction-app-ecs` ← `app`:8080 | 연결하지 않음 |
| 헬스체크 | ALB가 8080 `/readyz` | 컨테이너 헬스체크 8081 `/actuator/health/liveness` (시작 대기 120초) |
| 배포 | 최소 100% / 최대 200%: 새 task가 healthy가 된 뒤 이전 task를 내린다 | 최소 0% / 최대 100%: 이전 task를 내린 뒤 새 task를 띄운다 (스케줄러가 겹치지 않게) |
| 서킷 브레이커 | 켬, 실패하면 이전 task definition으로 자동 롤백 | 같음 |
| 기타 | `healthCheckGracePeriodSeconds` 120 (Flyway 포함 기동 시간), ECS Exec 켬 | ECS Exec 켬 |

공통
- Fargate, ARM64(Graviton), task 1 vCPU / 2GB, 퍼블릭 IP 없음, app SG
- TG `attraction-app-ecs`의 등록 해제 지연은 60초다.
- 서킷 브레이커가 실패를 판정하는 데 task 1개 기준 약 15분 걸린다 (2026-11-08 실측).

### task 구성

| 컨테이너 | 이미지 | essential | 역할 |
|---|---|---|---|
| `app` | ECR `attraction-reservation:<커밋 SHA>` | O | Spring 애플리케이션. 8080 서비스, 8081 actuator. 메모리 1400MB, `stopTimeout` 30초 |
| `log-router` | ECR `attraction-reservation-fluent-bit:<사이드카 SHA>` | O | FireLens. app stdout(logfmt)을 Loki로 보낸다. 죽으면 task가 내려간다 |
| `alloy` | ECR `attraction-reservation-alloy:<사이드카 SHA>` | X | `localhost:8081/actuator/prometheus`를 15초마다 수집해 Prometheus로 보낸다. 죽어도 task는 남는다 (알림 "api 메트릭 끊김"이 잡는다) |

- 앱 이미지는 [`Dockerfile`](../../Dockerfile)로 만든다. jar를 레이어로 풀어 넣고, JVM 옵션(`MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`)은 `ENTRYPOINT`에 직접 둔다. `JAVA_TOOL_OPTIONS`를 쓰면 logfmt가 아닌 `Picked up ...` 줄이 로그에 섞이기 때문이다.
- 사이드카 이미지 설정은 [`deploy/ecs/fluent-bit/`](../../deploy/ecs/fluent-bit), [`deploy/ecs/alloy/`](../../deploy/ecs/alloy)에 있다. Fargate는 호스트 파일을 마운트할 수 없어 설정을 이미지에 넣는다. 태그는 두 디렉터리를 마지막으로 바꾼 커밋 SHA다.
- 로그·메트릭 레이블은 `app=attraction-reservation`, `env=prod`, `service=api|worker`, `instance=<task ID>`다.
- 사이드카 자체 로그는 CloudWatch Logs `/ecs/attraction-reservation`(보존 7일)에 남는다. app 로그는 CloudWatch에 남기지 않는다.

### 운영 설정 (`application-prod.yml`)

- DB·Redis 주소는 기본값 없이 환경변수로만 받는다. 값이 빠지면 localhost로 붙지 않고 기동에 실패한다.
- actuator는 8081에서만 응답한다. readiness를 서비스 포트에도 `/readyz`로 열어, ALB가 서비스 포트가 요청을 받을 수 있는지 확인하게 한다.
- 배너를 끈다. 여러 줄 배너가 Loki에 logfmt가 아닌 줄로 섞이기 때문이다.

## 3. 설정값과 비밀값

### Parameter Store (`/attraction-reservation/prod/`)

| 이름 | 타입 | 쓰는 곳 |
|---|---|---|
| `env/JWT_SECRET`, `env/PORTONE_API_SECRET`, `env/PORTONE_WEBHOOK_SECRET`, `env/SPRING_DATASOURCE_PASSWORD` | SecureString (`alias/aws/ssm`) | task definition `secrets` → app 환경변수 |
| `env/PORTONE_CHANNEL_KEY`, `env/PORTONE_STORE_ID`, `env/REDIS_HOST`, `env/SPRING_DATASOURCE_URL`, `env/SPRING_DATASOURCE_USERNAME`, `env/SPRING_DATA_REDIS_SSL_ENABLED` | String | 같음 |
| `PROMETHEUS_URL` | String | alloy 사이드카 (`http://10.0.130.10:9090/api/v1/write`) |
| `APP_VERSION` | String | CD가 마지막으로 앱 배포에 성공한 커밋 SHA. 다음 배포의 변경 감지 기준 |
| `MONITORING_VERSION` | String | CD가 마지막으로 반영한 모니터링 구성의 커밋 SHA |
| `monitoring/GRAFANA_ADMIN_PASSWORD`, `monitoring/ALERT_WEBHOOK_URL` | SecureString | 모니터링 EC2의 `.env` (Grafana 관리자 비밀번호, Slack webhook) |
| `LOKI_URL` | String | EC2 Alloy가 쓰던 값. ECS는 쓰지 않는다 (7단계에서 삭제) |

- 앱 환경변수를 추가하면 SSM `env/`에 파라미터를 만들고, **두 task definition 파일의 `secrets`에도 추가**해야 한다. EC2 때처럼 `env/` 아래를 전부 읽지 않는다.
- 비밀값은 이미지, 저장소, GitHub에 두지 않는다.
- 2026-11-10 현재 `APP_VERSION`·`MONITORING_VERSION`은 `cd` 브랜치 시험 배포 커밋 `e401f4f8`이다(main에 없음). ECS로 바뀐 CD의 첫 main 배포가 기준 버전이 main의 조상이 아니라고 보고 앱·모니터링을 모두 다시 배포해 main SHA로 맞춘다.

### IAM 역할

| 역할 | 용도 |
|---|---|
| `attraction-ecs-execution-role` | ECS가 task를 띄울 때 사용. ECR pull, CloudWatch Logs 쓰기, SSM `env/*`·`PROMETHEUS_URL` 읽기 |
| `attraction-ecs-task-role` | task 안에서 사용. ECS Exec(`ssmmessages:*`)만 있다. 앱 코드는 AWS API를 호출하지 않는다 |
| `github-actions-role` | CD가 GitHub OIDC로 맡는 역할. 인라인 정책 `cd-deploy-ecs`, `cd-deploy-monitoring`, `cd-deploy-bundle-s3`, `cd-deploy-asg`(7단계에서 삭제) + `AmazonEC2ContainerRegistryPowerUser` |
| `monitoring-ec2-role` | 모니터링 EC2. S3 모니터링 번들 읽기·목록, SSM `MONITORING_VERSION`·`monitoring/*` 읽기, CloudWatch 지표 읽기, SSM 접속 |

`github-actions-role`의 신뢰 정책은 `sub`가 정확히 `repo:attraction-final@342808963/attraction-reservation@1421542612:environment:production`일 때만 허용한다. 저장소는 이름이 아닌 불변 ID 형식의 OIDC `sub`를 쓰므로, 저장소 이름을 바꿔도 신뢰 정책을 고칠 필요가 없다. `production` Environment 밖의 job은 이 역할을 맡을 수 없다.

### GitHub 설정

저장소 Actions 변수

| 변수 | 값 |
|---|---|
| `AWS_REGION` | `ap-northeast-2` |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::793168138824:role/github-actions-role` |
| `ECR_REPOSITORY` | `attraction-reservation` (사이드카는 `-fluent-bit`, `-alloy`를 붙인 저장소) |
| `ARTIFACT_BUCKET` | `attraction-reservation-793168138824-ap-northeast-2-an` |
| `SSM_PREFIX` | `/attraction-reservation/prod` |
| `ECS_CLUSTER` | `attraction-reservation` |
| `ECS_EXECUTION_ROLE_ARN`, `ECS_TASK_ROLE_ARN` | 위 두 ECS 역할 |
| `LOKI_HOST`, `LOKI_PORT` | `loki.attraction.internal`, `3100` |
| `ASG_NAME` | `attraction-reservation-app` (EC2 시절 값, 7단계에서 삭제) |
| `SONAR_PROJECT_KEY` | CI의 SonarQube Cloud 분석용 |

- 저장소 secret은 `SONAR_TOKEN` 하나다. AWS 자격 증명은 OIDC로 받으므로 secret에 두지 않는다.
- Environment `production`은 배포 브랜치를 `dev`, `main`, `cd`로 제한한다. `workflow_run`은 기본 브랜치(dev)에서 실행되므로 `dev`가 필요하고, `main`은 `workflow_dispatch`용이다. `cd`는 CD를 만들 때 쓰던 임시 브랜치라 지워야 한다 ([8장](#8-남은-작업)).

## 4. CI/CD

```
PR (→ dev, main) ──▶ CI: 테스트 + JaCoCo + SonarQube (PR 코멘트)

dev ──릴리스 PR──▶ main push ──▶ CI 성공 ──workflow_run──▶ CD
                                                           ├─ changes            배포 대상 판단
                                                           ├─ deploy-app         (앱 변경 시) ECS api → worker
                                                           └─ deploy-monitoring  (deploy/monitoring/ 변경 시) 모니터링 EC2
```

### CI ([`ci.yml`](../../.github/workflows/ci.yml))

- `main`, `dev`로 가는 PR과 `main` push에서 실행한다.
- `./gradlew test jacocoTestReport` → 테스트 결과를 PR Checks에 표시 → SonarQube Cloud 분석. push(main)에서는 Quality Gate 결과를 기다려 Job Summary에 남긴다.
- fork PR에는 secret이 전달되지 않으므로 SonarQube 분석은 같은 저장소의 PR과 push에서만 실행한다.

### CD ([`cd.yml`](../../.github/workflows/cd.yml))

시작 조건
- main push에서 CI가 성공했을 때(`workflow_run`). PR에서 돈 CI, fork에서 돈 CI는 배포하지 않는다.
- 자동 배포는 main의 최신 커밋만 한다. 늦게 끝나거나 재실행한 CI가 이전 커밋을 배포하지 않게 하기 위해서다. 건너뛴 커밋의 변경은 최신 커밋 배포에 함께 들어간다.
- 실행은 한 번에 하나씩, 취소 없이 순서대로 한다(`concurrency: cd-production`, `queue: max`).
- `workflow_run`은 **dev에 있는 `cd.yml`** 로 실행된다. `cd.yml`을 바꾸면 dev에 머지되는 순간 다음 main 배포부터 적용되므로, main에 있는 코드와 함께 동작하는지 확인하고 머지한다.

1) changes: 배포 대상 판단
- SSM `APP_VERSION`, `MONITORING_VERSION`부터 이번 커밋까지의 변경 파일을 본다.
- 앱: `deploy/monitoring/`, `docs/`, `.github/`, `*.md` 밖의 파일이 바뀌면 배포한다.
- 모니터링: `deploy/monitoring/`이 바뀌면 배포한다.
- 기준 버전이 없거나 이번 커밋의 조상이 아니면 변경이 있다고 본다.

2) deploy-app: 앱 배포 (ARM 러너 `ubuntu-24.04-arm`, 제한 60분)
1. 같은 SHA의 앱 이미지가 ECR에 없으면 `bootJar` → arm64 이미지 빌드 → push. 테스트는 CI에서 통과했으므로 다시 돌리지 않는다.
2. 사이드카 이미지가 없으면 빌드·push. ECR 저장소가 모두 태그 변경 불가(IMMUTABLE)라 이미 있는 태그는 다시 올리지 않는다.
3. [`deploy/ecs/*-task-definition.json`](../../deploy/ecs)의 자리 표시자(`<APP_IMAGE>` 등)를 채워 task definition 2개를 등록한다. 채울 값이 비었거나 채우지 못한 자리 표시자가 남으면 등록하지 않는다.
4. api 서비스를 갱신하고 새 배포의 `rolloutState`가 `COMPLETED`가 되면 worker를 갱신한다. `FAILED`이거나 배포가 목록에서 사라지면 실패로 끝난다(서킷 브레이커가 이전 버전으로 되돌린다). 서비스당 최대 20분 기다린다.
5. 두 서비스가 모두 성공한 뒤에만 `APP_VERSION`을 이번 SHA로 바꾼다. 실패하면 그대로 두어 다음 배포가 같은 변경을 다시 배포한다. worker만 실패하면 api만 새 버전으로 남고 다음 배포가 둘을 맞춘다.

7단계 전까지는 ASG 롤백에 대비해 실행 구성(`deploy/compose.yml` 등)도 S3 `attraction-reservation/<SHA>/`에 올린다.

3) deploy-monitoring: 모니터링 배포
1. `deploy/monitoring/`을 S3 `attraction-reservation/monitoring/<SHA>/`에 올리고 `MONITORING_VERSION`을 바꾼다.
2. SSM Run Command로 모니터링 EC2(Name 태그 `attraction-reservation-monitoring`)에서 실행한다.
   - `/opt/monitoring`으로 받는다(`s3 sync --delete`).
   - SSM에서 `.env`(Grafana 비밀번호, Slack webhook)를 다시 만든다.
   - `docker compose up -d`를 실행한다.
   - 설정 파일을 다시 읽도록 Prometheus, Loki, Grafana를 재시작한다.
3. 실패하면 `MONITORING_VERSION`을 이전 값으로 되돌려 다음 배포에서 다시 반영하게 한다. 인스턴스에 이미 받은 파일은 되돌리지 않는다.

### 롤백

| 상황 | 방법 |
|---|---|
| 새 task가 뜨지 않거나 헬스체크 실패 | 서킷 브레이커가 자동으로 이전 task definition으로 되돌린다. CD job은 실패로 끝나고 `APP_VERSION`은 바뀌지 않는다 |
| 배포는 성공했지만 동작이 잘못됨 | Actions → CD → Run workflow (main)에서 `version`에 되돌릴 커밋 SHA(40자)를 넣는다. main 이력에 있고 `deploy/ecs/`가 들어 있는 커밋만 배포할 수 있다. 앱만 배포한다 |

- 수동 롤백은 긴급 조치다. 다음 main 배포는 롤백한 버전부터 비교하므로 문제 커밋도 다시 배포된다. 다음 push 전에 main에서 문제 커밋을 revert한다.
- ECR `attraction-reservation`의 amd64 이미지 `31733ed`로는 롤백하지 않는다. Fargate ARM64에서 실행되지 않는다.

### 배포 이력

- 앱: ECS 서비스의 deployment 목록, CD Job Summary(배포·이전 버전, task definition revision)
- 기준 버전: SSM `APP_VERSION`, `MONITORING_VERSION`의 값과 수정 시각

## 5. DB 스키마

- Flyway가 앱 기동 시 `src/main/resources/db/migration/`의 새 파일을 적용하고, Hibernate가 `ddl-auto: validate`로 엔티티와 스키마가 맞는지 확인한다. 운영 DB에 직접 DDL을 실행하지 않는다. 작성 규칙은 [migration.md](../database/migration.md)를 따른다.
- 운영 DB의 `flyway_schema_history` 기록: V1 baseline, V2(2026-11-04), V3(2026-11-07, ECS api task 첫 기동 시).
- api 배포는 새 task를 먼저 띄우고 이전 task를 나중에 내린다. 그동안 이전 버전 코드도 새 스키마로 동작하므로 마이그레이션은 하위 호환되게 쓴다. 예를 들어 컬럼을 추가할 때는 NULL을 허용하고, 삭제·이름 변경은 배포를 나눠서 한다.

## 6. 관측 (모니터링 EC2)

| 항목 | 값 |
|---|---|
| 인스턴스 | `i-0b91df092531d5b72`, t4g.medium, 사설 IP `10.0.130.10` 고정, 역할 `monitoring-ec2-role`, IMDS hop limit 2 (Grafana 컨테이너가 인스턴스 역할로 CloudWatch를 읽는다) |
| 실행 구성 | [`deploy/monitoring/`](../../deploy/monitoring), 처음 기동은 [`deploy/monitoring/user-data.sh`](../../deploy/monitoring/user-data.sh), 이후 변경은 CD `deploy-monitoring` |
| Prometheus | v3.15.1, 보존 15일, remote write 수신(9090) |
| Loki | 3.7.9 (3100) |
| Grafana | 13.3.0, `https://grafana.xevbn.click`. ALB에서 Cognito 인증(셀프 가입 끔)을 거친 뒤 Grafana 자체 로그인을 한 번 더 한다 |
| 데이터 | 루트 볼륨 `/data` (인스턴스를 지워도 볼륨은 남도록 `DeleteOnTermination=false`) |

- 데이터소스: Prometheus, Loki, CloudWatch(RDS·ElastiCache·ALB)
- 대시보드: `deploy/monitoring/grafana/dashboards/`의 파일로 프로비저닝한다. Grafana 화면에서 고친 내용은 파일로 옮겨야 남는다.
- 알림: `deploy/monitoring/grafana/provisioning/alerting/alerts.yml`. 규칙은 6개다(api 정상 대상 0, api 메트릭 끊김, worker 스케줄러 멈춤, api 5xx, 결제·환불 최종 실패, ERROR 로그). 알림은 Slack으로 보낸다. ERROR 로그 규칙은 운영에서 아직 일시정지 상태이고, 일시정지를 푼 파일이 다음 모니터링 배포 때 반영된다. 자세한 기준은 [ecs-migration-plan.md 6단계](ecs-migration-plan.md#6단계-알림-grafana-alerting)를 참고한다.
- 로그 형식과 조회 방법은 [logging.md](../logging/logging.md)를 따른다.
- Grafana는 인스턴스에서 3000 포트로 열려 있고, 보안 그룹이 ALB에서 오는 요청만 받는다. 저장소의 `compose.yml`에는 KAN-154(PR #273)로 반영된다.
- Cognito나 ALB에 문제가 있으면 SSM 포트 포워딩으로 접속한다. 세션은 20분 동안 쓰지 않으면 끊긴다(Session Manager 기본값).

```bash
aws ssm start-session --target i-0b91df092531d5b72 \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'
```

모니터링 EC2를 새로 만들 때
- 사설 IP `10.0.130.10`, Name 태그, 인스턴스 프로파일, IMDS 설정을 `deploy/monitoring/user-data.sh` 상단 주석대로 지정한다.
- IP를 바꾸면 Route 53 `loki.attraction.internal` 레코드와 SSM `PROMETHEUS_URL`을 고친다. 그다음 ECS 서비스를 다시 배포해 새 값을 읽게 한다.
- `MONITORING_VERSION`이 `none`이면 인스턴스가 기동하지 못한다. S3 `monitoring/<SHA>/`에 번들이 있는 SHA로 먼저 고친다.

## 7. 자주 하는 작업

| 작업 | 방법 |
|---|---|
| 앱 배포 | dev → main 릴리스 PR을 머지한다. CI 성공 후 CD가 자동으로 배포한다 |
| 롤백 | [4장 롤백](#롤백) |
| 앱 환경변수 추가·변경 | SSM `env/<이름>`을 만들거나 고친다. 새 변수면 `deploy/ecs/api-task-definition.json`, `worker-task-definition.json`의 `secrets`에도 추가하고 배포한다. 값만 바꿨으면 서비스를 강제로 새로 배포해 다시 읽게 한다 (`aws ecs update-service --cluster attraction-reservation --service <api\|worker> --force-new-deployment`) |
| 컨테이너 접속 | `aws ecs execute-command --cluster attraction-reservation --task <task ID> --container app --interactive --command sh` |
| 사이드카 오류 확인 | CloudWatch Logs `/ecs/attraction-reservation` (스트림 접두사 `api-log-router`, `api-alloy`, `worker-…`) |
| Grafana 대시보드·알림 변경 | `deploy/monitoring/` 아래 파일을 고쳐 main에 반영한다. CD `deploy-monitoring`이 적용한다 |
| 모니터링 비밀값 변경 | SSM `monitoring/*`를 고친다. 다음 `deploy-monitoring`이 `.env`를 다시 만든다 |

## 8. 남은 작업

ECS 전환 (자세한 내용은 [ecs-migration-plan.md](ecs-migration-plan.md))
- [ ] 4단계: ECS로 바뀐 CD를 첫 main 릴리스로 실행해 api → worker 배포를 확인하고 배포 시간을 잰다. 같은 SHA로 `workflow_dispatch` 재배포도 확인한다. **이 PR이 dev에 머지되기 전에는 main에 push하지 않는다.** 이전 CD가 인스턴스 0대인 ASG에 Instance Refresh를 시도하기 때문이다.
- [x] 6단계: Slack 테스트 알림, worker 멈춤·해제 알림 확인 (2026-11-10 `cd` 시험 배포). ERROR 로그 알림 일시정지 해제는 다음 모니터링 배포 때 운영에 반영된다
- [ ] KAN-154(PR #273)를 먼저 dev에 머지하고, 이 브랜치에 dev를 합친다. `deploy/monitoring/compose.yml`이 충돌하므로 양쪽을 합친다(`ALERT_WEBHOOK_URL` + 포트 `3000:3000`). 이 순서를 지키지 않으면 main 배포 때 Grafana가 `127.0.0.1:3000`으로 돌아가 ALB 접속이 끊긴다
- [ ] 7단계: EC2 롤백 경로 정리
  - 삭제할 AWS 리소스: ASG `attraction-reservation-app`(desired 0), Launch Template `lt-068d563012732fb65`, TG `attraction-final`, `ec2-role`, 인라인 정책 `cd-deploy-asg`, S3 앱 실행 구성, SSM `LOKI_URL`, Actions 변수 `ASG_NAME`
  - ECR lifecycle 정책 추가: 3개 저장소 모두 아직 정책이 없다. 앱 이미지 16개가 쌓여 있다
  - 저장소에서 삭제: `deploy/compose.yml`, `deploy/user-data.sh`, `deploy/alloy/`
  - 이 문서와 로깅 규칙 10장을 ECS 기준으로 고친다

조회하다 발견한 것
- [ ] 첫 main 배포 뒤 `cd` 브랜치를 지우고, Environment `production`의 배포 브랜치에서도 `cd`를 뺀다 (CD를 만들 때 쓰던 임시 브랜치. 2026-11-10 시험 배포에 다시 썼다)
- [ ] ECR 저장소 `alloy`의 출처를 확인한다. 저장소 이름 규칙(`attraction-reservation-alloy`)과 맞지 않고 CD가 쓰지 않는다. 쓰지 않으면 지운다

전환과 별개 (범위 밖으로 둔 것)
- api task 2개 이상(여러 AZ): 지금은 api·worker·NAT·RDS가 모두 단일 장애점이다
- RDS Multi-AZ, ElastiCache 복제본
- Prometheus 주소도 프라이빗 DNS 이름으로 통일 (`PROMETHEUS_URL`이 IP)
- worker를 여러 개로 늘릴 때 ShedLock 도입 검토
