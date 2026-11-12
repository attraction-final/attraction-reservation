# ADR: 애플리케이션 실행 환경을 EC2(ASG)에서 ECS Fargate로 전환

| 항목 | 내용 |
|---|---|
| 상태 | 채택 (Accepted) |
| 작성일 | 2026-11-06 |
| 갱신일 | 2026-11-12 (전환 후 실제 구성과 결과 측정 반영) |
| 범위 | Spring 애플리케이션 실행 환경. 모니터링 스택(Prometheus·Loki·Grafana)은 EC2에 유지한다 |
| 실행 계획 | [ecs-migration-plan.md](ecs-migration-plan.md) |

## 1. 요약

- 전환 전에는 **ASG + Launch Template user-data + docker compose + CD 스크립트**를 조합해 컨테이너 오케스트레이션을 직접 구현하고 있었다.
- 이 조합은 동작하지만 배포가 느리고(약 14.5분), 롤백이 CD 작업에 의존하며, 인스턴스 기동이 여러 외부 의존성에 묶여 있고, 저장소와 운영 설정이 어긋날 수 있다.
- **ECS Fargate**로 옮겨 이 역할을 AWS 관리형 오케스트레이터에 맡긴다. 같은 이미지를 **API 서비스**와 **worker 서비스(스케줄러 전용)**로 나눠 실행한다.
- ECS 전환은 **배포·운영 안정성**을 높이는 결정이다. 서비스 가용성(단일 장애점 제거)은 task를 2개 이상 실행해야 얻을 수 있으며, 이는 전환과 별개로 필요하다.

## 2. 배경: 전환 전 구조

```
GitHub Actions(CD)
  ├─ arm64 이미지 빌드 → ECR (<커밋 SHA> 태그)
  ├─ 실행 구성(compose.yml, Alloy 설정) → S3 <SHA>/
  ├─ SSM APP_VERSION 갱신
  └─ ASG Instance Refresh → 실패 시 APP_VERSION 되돌리고 다시 교체

새 EC2 인스턴스(t4g.small) 기동 시 user-data
  dnf install docker → GitHub에서 docker compose 다운로드 → S3 번들 → SSM 값으로 app.env 생성
  → ECR 로그인 → docker compose up (app + Alloy)

Alloy: docker.sock으로 app stdout 수집 → Loki, app:8081 스크랩 → Prometheus remote_write
```

| 역할 | 담당 |
|---|---|
| 실행할 컨테이너 정의 | `deploy/compose.yml` |
| 인스턴스 준비 | `deploy/user-data.sh` (Launch Template에 복사본으로 들어감) |
| 몇 개 띄울지, 죽으면 교체 | ASG (1/1/2) + ALB 헬스체크 |
| 배포·롤백 | `cd.yml`의 Instance Refresh와 직접 만든 롤백 단계 |

## 3. 문제

운영하면서 실제로 겪었거나 구조상 드러난 문제다.

### 3-1. 배포와 롤백
- **배포 시간**: Instance Refresh 기반 배포가 약 14.5분 걸렸다(실측). 롤백도 인스턴스를 다시 교체하므로 비슷하게 걸린다.
- **롤백이 CD 작업에 의존**: 교체 실패를 감지하고 되돌리는 로직이 GitHub Actions 작업 안에 있다. 작업이 중간에 끊기면 롤백되지 않는다.
- **"헬스체크는 통과했지만 실제로는 깨진" 배포를 감지하지 못한다**: readiness가 정상이면 5xx가 급증해도 배포는 성공으로 끝난다.

### 3-2. 인스턴스 기동의 외부 의존성
- 새 인스턴스는 뜰 때마다 dnf 저장소, GitHub(compose 바이너리), S3, SSM, ECR에 의존한다. 이 중 하나라도 실패하면 인스턴스가 서비스에 들어가지 못한다.
- 새 인스턴스가 필요한 순간은 장애 교체·스케일아웃처럼 **급한 순간**이라 영향이 크다.

### 3-3. 설정 불일치(drift)
- Launch Template의 user-data는 저장소 파일을 **손으로 복사**한 것이다. 저장소를 고치고 LT 새 버전을 만들지 않으면 운영이 조용히 달라진다.
- 실제로 Windows 작업 트리의 CRLF를 제거해야 했고, compose 버전 차이(`env_file format: raw`) 때문에 LT 새 버전을 만들어야 했다.

### 3-4. 운영 부담
- AMI, Docker, compose 버전을 직접 관리해야 한다.
- 인스턴스 안의 상태(로그 파일 순환, 디스크)를 신경 써야 한다.

### 3-5. 스케줄러와 API가 같은 프로세스에서 실행
- 스케줄러 5개(주문 만료 취소, 웹훅, PG 보상 취소, 환불 재시도, 티켓 만료)가 API와 같은 JVM의 CPU·메모리·DB 커넥션을 나눠 쓴다. (결정 당시 기준. 이후 슬롯 생성 스케줄러가 추가되어 6개다)
- 인스턴스가 늘면 스케줄러도 같은 수만큼 실행된다. 결과는 멱등 처리(행 잠금 + 상태 재확인, 조건부 UPDATE)로 안전하지만, 같은 대상을 두고 잠금을 다툰다.

## 4. 결정 기준

1. 배포 실패를 사람이나 CD 작업 없이 자동으로 되돌릴 수 있는가
2. 인스턴스·컨테이너 기동 경로가 단순한가 (외부 의존성 수)
3. 운영 중인 설정이 저장소와 항상 같은가
4. 운영 부담(OS·런타임·클러스터 관리)이 작은가
5. 비용이 현재 규모(앱 1개, 소규모 팀)에 맞는가
6. 기존 관측 체계(Loki·Prometheus·Grafana, logfmt 규칙)를 유지할 수 있는가

## 5. 검토한 선택지

| 선택지 | 요약 | 장점 | 단점 |
|---|---|---|---|
| A. 현재 구조 유지 + 개선 | ASG를 2대로 늘리고 알람·스크립트를 보강 | 추가 학습·전환 비용 없음 | 3-1~3-4의 구조적 문제는 남음. 롤백·기동 로직을 계속 직접 관리 |
| **B. ECS Fargate** | AWS 관리형 오케스트레이터 + 서버리스 컴퓨팅 | 서킷 브레이커·알람 기반 자동 롤백, 기동 경로 단순화, task definition 리비전으로 설정 고정, OS 관리 없음, 제어부 무료 | 같은 사양 EC2보다 단가가 높음. docker.sock이 없어 로그 수집 방식을 바꿔야 함. 환경변수 추가 시 task definition 수정 필요 |
| C. ECS on EC2 | ECS가 EC2 용량 위에서 실행 | Fargate보다 저렴. docker.sock을 쓸 수 있어 Alloy 방식 유지 가능 | 용량 공급자·AMI·인스턴스 관리가 남음. 운영 부담 감소 효과가 작음 |
| D. EKS | 관리형 Kubernetes | 표준 API, Helm·Operator 생태계, 모니터링까지 같은 플랫폼에서 운영 가능 | 제어부 비용(클러스터당 시간당 $0.10)이 현재 앱 서버 비용보다 큼. 정기 버전 업그레이드와 애드온 관리 필요. 현재 규모에 과함 |
| E. 스케줄러를 Lambda로 분리 | EventBridge Scheduler + Lambda | 스케줄러를 API에서 분리 | 최소 주기 1분이라 10초·30초 주기 불가. at-least-once라 멱등 처리는 여전히 필요. 도메인 코드 중복, VPC·콜드 스타트·DB 커넥션 관리 부담 |

### ECS와 EKS의 관계
둘 다 컨테이너 오케스트레이터다. ECS는 AWS 전용으로 단순하고 AWS 서비스(ALB·IAM·SSM)와 기본 통합된다. EKS는 Kubernetes 표준이라 이식성과 생태계가 크지만 관리할 것이 많다. 서비스 1개, 소규모 팀에서는 ECS가 맞다.

### 스케줄러를 Lambda로 옮기지 않는 이유
현재 스케줄러는 결제·주문 도메인의 Processor를 즉시 처리 경로와 공유하고, 주기가 짧다(10초~1분). 같은 이미지를 **worker 서비스**로 따로 띄우면 Lambda로 얻으려던 분리 효과(자원 격리, API와 독립된 실행)를 코드 중복 없이 얻는다.

## 6. 결정

**선택지 B (ECS Fargate)** 를 채택한다.

- 같은 이미지로 두 서비스를 실행한다.

| 서비스 | ALB 연결 | 프로필 | task 수 | 배포 방식 | 롤백 조건 |
|---|---|---|---|---|---|
| `api` | 연결 | `prod,api,oauth` (스케줄러 OFF) | 1~3 (CPU 60% 기준 오토스케일링) | 새 task가 healthy가 된 뒤 이전 task를 내림 (min 100% / max 200%) | 서킷 브레이커 + 5xx 알람 |
| `worker` | 연결하지 않음 | `prod` (스케줄러 ON) | 1 고정 | 이전 task를 내린 뒤 새 task를 띄움 (min 0% / max 100%). 스케줄러가 겹치지 않는 대신 배포 중 잠시 멈춤 | 서킷 브레이커 |

- 스케줄러는 `*.scheduler.enabled` 속성으로 켜고 끈다. `api` 프로필(`application-api.yml`)에서 6개를 모두 끈다.
- 로그는 FireLens(Fluent Bit) 사이드카로 Loki에, 메트릭은 Alloy 사이드카로 Prometheus에 보낸다. 기존 대시보드와 logfmt 규칙을 유지한다.
- 모니터링 스택은 EC2에 유지한다. 상태(디스크)를 가진 단일 컨테이너들이라 Fargate로 옮겨 얻는 이점이 없다.

### worker란
사용자 요청을 받지 않고 뒤처리만 하는 실행 단위다. 별도 코드가 아니라 **같은 jar를 스케줄러만 켠 설정으로 하나 더 띄운 것**이다. ALB에 연결하지 않아 API 요청이 들어올 경로가 없다.

```
사용자 ─▶ ALB ─▶ api task들     (요청 처리, 스케줄러 OFF, 1~3개)
                 worker task    (스케줄러만, 요청 경로 없음)
                 둘 다 같은 RDS · Redis · PG 사용
```

### 배포 실패 감지
ECS는 두 방법으로 배포 실패를 감지하고, 먼저 조건에 걸린 쪽이 이전 배포로 롤백한다. CD는 배포 상태(`rolloutState`)를 확인해 결과만 보고하고 롤백하지 않는다.

| 방법 | 감지 대상 | 대상 서비스 |
|---|---|---|
| 배포 서킷 브레이커 | task가 기동하지 못하거나 헬스체크(`/readyz`)를 통과하지 못하는 배포 | api, worker |
| CloudWatch 알람 `attraction-reservation-api-5xx` | 헬스체크는 통과했지만 5xx가 급증하는 배포 (3-1) | api (ALB에 붙은 서비스만 해당) |

알람 기준과 이유 (정의는 [terraform.md](terraform.md)의 `ecs.tf`)

- ALB 대상 그룹의 **앱이 낸 5xx**(`HTTPCode_Target_5XX_Count`)와 요청 수(`RequestCount`)를 1분 단위로 본다.
- **1분에 5xx 5건 이상이면서 요청의 5% 초과**인 분이 **최근 3분 중 2분**이면 ALARM이 된다. 건수와 비율 기준은 Grafana "api 5xx" 알림과 같다.
- 5분 창을 쓰지 않는 이유: ECS는 배포가 끝난 뒤 bake time(5분 미만) 동안만 알람을 본다. 5분 창으로 판정하면 판정이 나오기 전에 감시가 끝날 수 있다.
- 3분 중 2분을 조건으로 두어, 1분에 한 번 튄 5xx로는 롤백하지 않는다.
- ELB가 직접 반환하는 5xx(`HTTPCode_ELB_5XX_Count`)는 Grafana 알림 기준과 맞추기 위해 보지 않는다.
- 알람은 롤백 전용이라 `alarm_actions`를 두지 않는다. 사람에게 가는 알림은 Grafana가 Slack으로 보낸다.

## 7. 기대 효과와 한계

### 개선되는 것
| 영역 | 전환 전 | ECS 전환 후 |
|---|---|---|
| 배포 실패 롤백 | CD 작업 안의 스크립트 | 배포 서킷 브레이커가 자동 롤백 |
| 헬스체크 통과 후 5xx 급증 | 감지 못함 | CloudWatch 알람 연동 자동 롤백 (2026-11-12 시험, 9장) |
| 배포·롤백 시간 | 약 14.5분 (실측) | 배포 약 9~10분, 롤백 약 1.5분 (9장) |
| 기동 경로 | dnf, GitHub, S3, SSM, ECR | ECR, SSM |
| 설정 일관성 | LT 복사본과 저장소가 어긋날 수 있음 | CD가 task definition 리비전을 등록 |
| OS·런타임 관리 | AMI, Docker, compose 직접 관리 | AWS 관리 |
| 스케줄러 | API와 같은 JVM, 인스턴스 수만큼 실행 | worker 1개에서만 실행, API와 자원 분리 |

### 해결되지 않는 것
| 항목 | 이유와 대응 |
|---|---|
| 앱 단일 장애점 | task가 1개면 지금과 같다. api task를 2개 이상, 여러 AZ에 배치해야 한다. 오토스케일링 최솟값은 아직 1이다 |
| NAT 게이트웨이 단일 AZ | NAT가 2a에만 있다. api task를 여러 AZ에 두어도 2a 장애 시 외부 호출(PG, ECR pull)이 끊긴다 |
| 모니터링 EC2, ElastiCache 단일 노드, RDS | 이번 범위 밖이다. 각각 별도로 검토한다 |
| JVM 프로세스 재시작 속도 | 전환 전에는 compose가 같은 인스턴스에서 바로 재시작했다. ECS는 새 task를 띄우므로 이 경우는 비슷하거나 약간 느릴 수 있다 |

### 새로 생기는 위험
| 위험 | 대응 | 상태 |
|---|---|---|
| 로그 사이드카 장애 | log-router가 `essential: true`라 죽으면 task 전체가 중단된다(로그 없이 실행되지 않도록 의도). 전송 실패에 대비한 디스크 버퍼(`storage.type filesystem`)를 검토 | 검토 |
| 메트릭 사이드카 장애 | Alloy는 `essential: false`라 실패해도 앱은 유지된다. Grafana "api 메트릭 끊김" 알림 | 적용 |
| 배포 시작 시점에 알람이 이미 ALARM | ECS가 그 배포 동안 알람을 보지 않는다(장애를 고치는 배포가 막히지 않게 하는 AWS 동작). 이때는 서킷 브레이커만 동작 | 인지 |
| 트래픽이 적은 시간의 5xx 급증 | 1분 5건 기준을 넘지 못해 알람이 울리지 않을 수 있다. Grafana 알림(5분 기준)으로 사람이 대응 | 인지 |
| 새 스케줄러가 api에서도 실행됨 | 스케줄러가 `matchIfMissing = true`라 `application-api.yml`에 `enabled: false`를 빠뜨리면 api task 수만큼 실행된다. 스케줄러를 추가하는 절차에 포함 | 절차 |
| worker 중단·배포 중 스케줄러 공백 | Grafana "worker 스케줄러 멈춤" 알림(10분간 실행 0회). 처리 대상은 DB에 남아 새 task가 이어서 처리 | 적용 |
| Fargate 플랫폼 유지보수로 인한 task 교체 | api task 2개 이상 운영 | 계획 |
| task 기동마다 이미지 pull (NAT 비용, ECR 의존) | S3 게이트웨이 엔드포인트로 레이어 수신. 필요하면 ECR VPC 엔드포인트 검토 | 일부 적용 |
| 환경변수 추가 시 task definition 수정 필요 | 운영 절차 문서화 | 절차 |

## 8. 비용

서울 리전(ap-northeast-2) 온디맨드 단가 기준(AWS Pricing API, 2026-11-07 조회). 한 달은 730시간으로 계산한다.

| 항목 | 단가 |
|---|---|
| Fargate ARM vCPU | $0.03725 / 시간 |
| Fargate ARM 메모리 | $0.00409 / GB·시간 |
| t4g.small | $0.0208 / 시간 |
| NAT 게이트웨이 | $0.059 / 시간 + $0.059 / GB |

| 구성 | 시간당 | 월 |
|---|---|---|
| 전환 전: t4g.small 1대 | $0.0208 | 약 $15 |
| 현재: api 1 + worker 1 (각 1 vCPU / 2GB) | $0.0909 | 약 $66 |
| 가용성 확보 시: api 2 + worker 1 | $0.1363 | 약 $100 |
| 참고: EC2로 API·worker 분리 (t4g.small 2대) | $0.0416 | 약 $30 |

- 늘어나는 비용은 worker task 추가와 Fargate 단가 차이에서 나온다. EC2로 같은 분리를 해도 약 $30이므로, Fargate 자체로 늘어나는 비용은 월 약 $36이다. EC2 쪽 EBS 루트 볼륨(월 $1~3)은 계산에서 뺐다.
- t4g.small은 2 vCPU 버스트형(크레딧 소진 시 제한)이고, Fargate 1 vCPU는 항상 보장된다. 사양을 1:1로 비교할 수는 없다.
- api는 CPU 60% 기준으로 1~3개로 오토스케일링하므로, 부하가 몰리면 위 표보다 비용이 늘 수 있다.
- task 크기는 실측 후 확정한다. worker는 0.5 vCPU / 2GB로 줄일 수 있을 가능성이 크다(시간당 $0.0268).
- ECS 제어부는 무료다 (EKS는 클러스터당 시간당 $0.10).
- 바뀌지 않는 비용: ALB(기존 ALB 재사용, TG 추가는 무료), RDS, ElastiCache, 모니터링 EC2. 롤백용 CloudWatch 알람은 지표 2개짜리 알람 1개라 비용이 아주 작다.
- NAT 게이트웨이(월 약 $43)는 유지한다. task를 public 서브넷에 두면 NAT를 없앨 수 있지만, 보안 그룹 하나만으로 막게 되고 나가는 IP가 고정되지 않으며 모니터링 EC2의 인터넷 경로도 사라진다. ECR 이미지 레이어는 S3 게이트웨이 엔드포인트로 받으므로 NAT 처리량은 크게 늘지 않는다.
- 그 밖: 사이드카 이미지 저장(ECR), 사이드카 로그(CloudWatch Logs, 보존 7일).

## 9. 결과 측정

| 지표 | 전환 전 | 전환 후 |
|---|---|---|
| 배포 시간 (CD 시작 → 완료) | 약 14.5분 | 약 8.8~10.2분 (최근 ECS 배포 4건) |
| 롤백 시간 | 배포 시간과 비슷 | 약 1.5분 (실패 판정 → 이전 배포 steady state, 알람 롤백 시험) |
| 배포 실패 자동 복구 여부 | CD 작업 의존 | ECS가 자동 롤백 (서킷 브레이커 + 5xx 알람, CD 작업과 무관) |
| 기동 실패 지점 수 | 5 (dnf, GitHub, S3, SSM, ECR) | 2 (ECR, SSM) |
| 스케줄러 실행 위치 | 모든 앱 인스턴스 | worker 1개 |

### 배포 시간
CD 실행의 시작부터 완료까지 걸린 시간이다. 앱 이미지를 다시 빌드한 배포만 셌다.

| CD 실행 | 전체 | 빌드·이미지 푸시 | 서비스 배포 (api → worker 순서) |
|---|---|---|---|
| 2026-11-10 08:09 UTC (사이드카 이미지 포함) | 10분 14초 | 약 1분 50초 | 7분 38초 |
| 2026-11-10 14:15 UTC | 9분 6초 | 약 1분 20초 | 6분 49초 |
| 2026-11-12 03:06 UTC | 10분 10초 | 약 1분 20초 | 7분 38초 |
| 2026-11-12 07:57 UTC | 8분 45초 | 약 45초 | 6분 34초 |

- 시간 대부분은 서비스 배포다. api 새 task가 대상 그룹 헬스체크를 통과하는 시간(30초 간격 5회 성공), 이전 task 연결 정리(60초), 이어지는 worker 배포가 차지한다.
- 네 건 모두 알람 롤백을 연결하기 전이다. 연결 후에는 api 배포 완료가 bake time(5분 미만)만큼 늦어질 수 있다.

### 알람 롤백 시험 (2026-11-12)
운영 api에서 같은 리비전을 다시 배포하는 동안 알람을 ALARM으로 만들어 확인했다.

- 배포 시작 30초 뒤 알람 기준을 임시로 "항상 넘음"으로 바꿔, 실제 평가로 ALARM이 되게 했다. 새 task가 트래픽을 받은 지 약 35초 만에 `deployment failed: alarm detected`가 기록되고, 이전 배포로 롤백했다(`ROLLBACK_SUCCESSFUL`). 시험 후 알람 설정은 Terraform으로 되돌렸다.
- `set-alarm-state`로 알람 상태를 강제로 바꾸는 방식으로는 롤백되지 않았다(2회). ECS가 알람을 감시하기는 했지만 롤백 근거로 쓰지 않았고, 원인은 확인하지 못했다. 다시 시험할 때는 알람 기준을 잠깐 바꾸는 방식을 쓴다.
- 실제 5xx가 급증할 때 알람이 기준대로 ALARM이 되는지는 시험하지 않았다. 알람이 실제 ALB 지표를 읽어 계산하는 것은 확인했다.

## 10. 관련 문서
- 실행 계획: [ecs-migration-plan.md](ecs-migration-plan.md)
- 인프라 코드(ECS 서비스, 롤백 알람): [terraform.md](terraform.md)
- 로깅 규칙(수집기 설정 10장): [../logging/logging.md](../logging/logging.md)
- 웹훅·보상 스케줄러 구조: [../payment/payment-webhook-plan.md](../payment/payment-webhook-plan.md), [../payment/payment-compensation.md](../payment/payment-compensation.md)
