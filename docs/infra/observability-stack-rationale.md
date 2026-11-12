# ADR: 관측 스택으로 Loki + Prometheus + Grafana 선택

| 항목 | 내용 |
|---|---|
| 상태 | 채택 (Accepted) |
| 작성일 | 2026-10 (외부 문서로 작성, 저장소로 옮김) |
| 갱신일 | 2026-11-12 (ECS Fargate 전환 후 실제 구성과 후속 작업 상태 반영) |
| 범위 | 애플리케이션 로그·메트릭 수집, 대시보드, 알림 |
| 관련 결정 | [ecs-migration-rationale.md](ecs-migration-rationale.md) (앱 실행 환경을 ASG에서 ECS Fargate로 전환) |

1~8장은 결정 당시(앱 서버가 EC2 Auto Scaling Group이던 때) 기록이다. 전환 후 달라진 구성은 9장, 후속 작업 진행 상태는 10장에 적는다.

## 1. 요약

로그는 Grafana Loki, 메트릭은 Prometheus, 시각화와 알림은 Grafana로 구성하고, 애플리케이션 서버와 분리된 모니터링 전용 EC2 인스턴스에 직접 구성한다. ELK(Amazon OpenSearch Service)는 저장 비용과 운영 부담이 프로젝트 규모에 비해 크고, 이 프로젝트에 필요한 것은 전문 검색보다 메트릭과 로그를 함께 보는 장애 대응 흐름이기 때문이다.

## 2. 배경

어트랙션 예약 서비스는 사용자가 로그인해 티켓을 구매하고, 그 티켓으로 어트랙션을 예약하는 시스템이다. 추천 시스템은 사용자가 어트랙션을 고르고, 선택된 어트랙션 범위 안에서 방문 순서를 추천한다. 티켓 결제는 outbox 패턴으로 이벤트를 발행한다.

- 예상 사용자는 방문객(평균 1.5만~3만 명, 피크일 5~6만 명)의 약 70%로, 평균 하루 1만~2.1만 명, 피크일 3.5만~4.2만 명이다.
- 개장 직후처럼 특정 시간대에 요청이 몰린다.
- 인프라는 Kubernetes 없이 애플리케이션 서버를 EC2 Auto Scaling Group으로 운영하고, 모니터링은 별도 EC2 인스턴스에 띄운다. (이후 ECS Fargate로 전환, 9장)

장애 시 "어느 도메인에서, 언제, 어떤 요청이" 실패했는지 빠르게 찾을 수 있어야 한다. 특히 티켓 구매부터 예약까지 한 건의 흐름을 티켓 ID, 결제 ID, outbox 이벤트 ID, 예약 ID로 끝까지 추적할 수 있어야 한다.

예상 로그량은 평균 하루 약 1~4GB(월 30~130GB), 피크일 하루 약 5~15GB다. 사용자당 요청 100~200건, 요청당 로그 약 1KB를 가정한 추정치다.

## 3. 의사결정 기준

1. **비용 구조**: 현재 비용뿐 아니라 로그량과 보존 기간이 늘 때 비용이 어떻게 증가하는가
2. **운영 부담**: 소규모 팀이 Kubernetes 없이 운영할 수 있는가
3. **장애 대응 흐름**: 메트릭 이상 감지에서 해당 시간대 로그 확인까지 한 화면에서 이어지는가
4. **도메인 추적성**: 예약·결제 ID로 단일 요청의 흐름을 따라갈 수 있는가
5. **이식성**: 특정 클라우드 벤더에 묶이지 않고, 오픈소스 표준(PromQL, OpenTelemetry)을 따르는가

## 4. 검토한 대안

| 대안 | 장점 | 단점 |
|---|---|---|
| A. ELK (Amazon OpenSearch Service) | 로그 본문 전체를 역색인해 임의 키워드 전문 검색이 강력하다 | 인덱스와 복제본 때문에 원본의 2배 이상을 EBS에 저장해야 하고, 데이터 노드가 상시 떠 있어야 한다. 메트릭은 주력이 아니다 |
| **B. Loki + Prometheus + Grafana (모니터링 EC2에 직접 구성)** | Loki는 레이블만 인덱싱하고 로그 본문은 압축해 저장한다. Prometheus는 메트릭의 사실상 표준이며, Grafana에서 같은 레이블로 메트릭과 로그를 오갈 수 있다 | 임의 키워드 검색이 느리고, 구성 요소를 직접 운영해야 한다 |
| C. Amazon CloudWatch Logs (+ 메트릭) | 운영할 것이 없고, 로그량이 하루 수 GB 이하일 때는 가장 저렴하다 | AWS에 종속되고, 로그량이 늘면 수집량 비례 과금으로 비용이 빠르게 증가한다 |
| D. 관리형 Prometheus·Grafana (AMP·AMG) | 메트릭·대시보드 운영 부담이 줄어든다 | AMG는 사용자 수 과금과 IAM Identity Center 설정이 필요하고, Loki 관리형 서비스가 없어 로그는 어차피 직접 운영해야 한다 |

## 5. 비교

기준별로 보면 B가 비용 확장성, 장애 대응 흐름, 이식성에서 앞서고, A는 전문 검색, C는 운영 부담에서 앞선다.

| 기준 | A. ELK (OpenSearch) | B. Loki + Prometheus | C. CloudWatch |
|---|---|---|---|
| 로그 저장 방식 | 본문 전체 역색인, EBS | 레이블만 인덱싱, 압축 후 S3 | AWS 관리형 |
| 저장 단가 | OpenSearch gp3 약 $0.13~0.14/GB·월(추정) × 원본의 2배 이상 | S3 약 $0.025/GB·월 × 압축으로 원본의 1/5~1/10 | 수집 $0.76/GB + 보관료 |
| 하루 100GB·30일 보존 시 월 비용 | 약 $2,200~2,300 | 약 $650~850 | 수집료만 약 $2,300 |
| 예상 로그량(평균 월 30~130GB)에서 | 최소 클러스터도 월 수백 달러 | 모니터링 EC2 월 $30~60 + S3 수 달러(추정) | 수집료만 월 약 $25~100 |
| 전문 검색 | 매우 강함 | 약함(레이블 필터 후 스캔) | 보통(Logs Insights) |
| 메트릭 연계 | 약함 | 강함(Grafana에서 레이블로 연결) | 보통 |
| 운영 부담 | 중간(관리형이나 샤드·인덱스 관리) | 높음(직접 구성) | 거의 없음 |
| 이식성 | 중간 | 높음(오픈소스 표준) | 낮음(AWS 종속) |

- 비용은 서울 리전(ap-northeast-2) 온디맨드 기준의 대략적 추정이다(2026년 10월 확인).
- CloudWatch Logs 수집 단가 $0.76/GB는 버지니아($0.50)보다 약 52% 높다.
- OpenSearch의 서울 인스턴스·gp3 단가는 공개 자료로 확인하지 못해, 도쿄 리전 수준(r6g.xlarge.search 시간당 약 $0.40)으로 추정했다. 확정 단가는 AWS OpenSearch 요금 페이지에서 서울 리전을 선택해 확인한다.
- B의 하루 100GB 비용(약 $650~850)은 산식을 남기지 않았다. S3 저장비만으로는 월 $15 안팎이라, 나머지는 Loki 수집·조회 컴퓨팅을 키우는 비용으로 본 값이다.
- B의 저장 방식(S3)은 결정 당시 계획이다. 실제 구성은 9장을 따른다.

## 6. 결정 및 근거

**B안(Loki + Prometheus + Grafana, 모니터링 전용 EC2에 직접 구성)** 을 채택한다.

### ELK 대신 Loki를 고른 이유
이 서비스의 로그 조회는 "결제 서비스의 특정 시간대 ERROR 로그"처럼 서비스·레벨·시간으로 범위가 정해진 경우가 대부분이다. 이는 레이블 인덱싱만으로 충분하며, 본문 전체 역색인 비용을 낼 이유가 없다. 로그량이 늘어도 Loki는 저장 비용만 소폭 증가하지만 OpenSearch는 EBS와 노드가 선형으로 증가한다.

### 메트릭과 로그의 연결
Prometheus와 Loki가 같은 레이블을 공유하므로, Grafana에서 결제 실패율 급증 패널을 보고 같은 시간대 결제 서비스 로그로 바로 넘어갈 수 있다. 이 흐름이 장애 대응의 핵심 요구사항이다.

### CloudWatch 대신 직접 구성을 고른 이유
운영이 쉽다는 CloudWatch의 장점은 인정한다. 평균 로그량에서는 수집료가 월 약 $25~100로 Loki 구성(월 $30~60)과 비슷하지만, 피크일과 사용자 증가에 비례해 비용이 계속 늘어난다. 또한 오픈소스 표준(PromQL, LogQL, OpenTelemetry)을 따라 벤더 종속을 피하고, 로그량 증가 시 비용이 수집량에 비례해 늘어나는 구조를 피하기 위해 직접 구성을 택했다.

### 관리형(AMP·AMG) 대신 직접 구성을 고른 이유
Amazon Managed Grafana는 사용자 수 과금과 IAM Identity Center 설정이 필요해 이 프로젝트 규모에 맞지 않고, AWS에는 Loki 관리형 서비스가 없어 어차피 일부는 직접 운영해야 한다. 이 규모에서는 세 구성 요소를 모니터링 전용 EC2 한 대에 Docker Compose로 함께 운영하는 편이 단순하고 저렴하다.

## 7. 트레이드오프와 완화책

결정 당시 계획한 완화책과 현재 상태다. "대체됨"은 ECS 전환으로 다른 방식이 같은 역할을 하게 된 것이다.

| 리스크 | 영향 | 결정 당시 완화책 | 현재 상태 |
|---|---|---|---|
| 임의 키워드 전문 검색이 느림 | 넓은 기간 검색 시 응답 지연 | 로그를 JSON 구조화하고 티켓·결제·outbox 이벤트·예약 ID와 trace ID를 필드로 남겨, 레이블로 범위를 좁힌 뒤 필드로 필터링 | **적용(형식 변경)**: JSON 대신 logfmt(`event=` + 표준 키)로 남기고 `\| logfmt`로 필터링한다. traceId는 Micrometer Tracing이 MDC로 넣고, Grafana에서 traceId를 누르면 같은 요청의 로그 전체를 조회한다 |
| 레이블 카디널리티 폭증 | Loki 성능 저하 | 레이블은 `service`, `env`, `level`로 제한하고 고유값은 레이블로 쓰지 않음 | **적용(구성 변경)**: 레이블은 `app`, `env`, `service`(api·worker), `instance`(ECS task ID). `level`, `event`, 대상 ID, traceId는 필드다. `instance`는 task별로 스트림을 나눠 전송 지연 시 `too far behind` 거부를 막는다 |
| 모니터링 인스턴스 단일 장애점 | 인스턴스 장애 시 모니터링 중단 | 앱 ASG와 분리 배치, Loki 청크는 S3에 저장, EBS 스냅샷 정기 백업, EC2 자동 복구 | **일부**: 앱과 분리 배치, 인스턴스를 지워도 데이터 볼륨은 남김(`delete_on_termination = false`). S3 저장, 정기 스냅샷, 자동 복구 알람은 저장소에 없다 |
| ASG 스케일 아웃·인으로 인스턴스 교체 | 신규 인스턴스 scrape 누락 | Prometheus `ec2_sd_configs`로 ASG 태그 기준 자동 탐색 | **대체됨**: task마다 Alloy 사이드카가 앱 메트릭을 수집해 Prometheus로 push(remote_write)하므로 대상 탐색이 필요 없다 |
| 스케일 인 시 미전송 로그 | 종료 인스턴스의 마지막 로그 유실 | ASG 수명주기 훅으로 종료 전 Alloy 전송 완료 대기 | **대체됨**: app 컨테이너가 log-router에 의존(`dependsOn`)해 log-router가 나중에 종료되고, `stopTimeout` 30초로 전송 시간을 확보한다 |
| Loki·Prometheus 자체 인증 없음 | 외부 노출 시 로그·메트릭 유출 | 프라이빗 서브넷에 두고 보안 그룹으로 앱 ASG와 Grafana만 접근 허용 | **적용**: 프라이빗 서브넷, 3100(Loki)·9090(Prometheus)은 app SG에서만, Grafana(3000)는 ALB에서만 허용하고 ALB에서 Cognito 인증 |
| 상시 고정 비용 | 모니터링 EC2 월 $30~60 상시 발생 | Graviton(t4g) 최소 사양, 데모 외 시간에는 인스턴스 중지 | **일부**: t4g.medium(Graviton). Slack 알림을 상시 운영하므로 인스턴스는 중지하지 않는다 |
| 직접 운영 부담 | OS 패치·디스크 관리를 직접 수행 | Terraform으로 코드화, SSM Patch Manager, 디스크 사용량 알림 | **일부**: Terraform 코드화(`deploy/terraform/monitoring.tf`), CD(deploy-monitoring)로 설정 배포. SSM Patch Manager와 디스크 사용량 알림은 없다 |
| Promtail EOL(2026년 3월) | 수집기 지원 종료 | 앱 인스턴스마다 Grafana Alloy 설치 | **대체됨**: ASG 시절에는 Alloy를 썼고, ECS에서는 로그는 FireLens(Fluent Bit), 메트릭은 Alloy 사이드카가 보낸다 |

## 8. 재검토 조건

다음 중 하나에 해당하면 이 결정을 다시 검토한다.

- 보안 감사나 고객 문의 대응으로 로그 본문 임의 검색이 주요 요구사항이 될 때 → 해당 로그만 OpenSearch로 분리하는 하이브리드 검토
- 로그량이 하루 수십 GB를 넘어 모놀리식 Loki 한 대로 감당하기 어려울 때 → Simple Scalable 모드 전환
- 운영 인력 부담이 프로젝트 목표보다 커질 때 → Grafana Cloud 또는 CloudWatch 전환 검토

## 9. 전환 후 달라진 구성 (2026-11-12 기준)

앱 실행 환경이 ECS Fargate로 바뀌면서([ecs-migration-rationale.md](ecs-migration-rationale.md)) 수집 경로가 바뀌었다. 스택(Loki·Prometheus·Grafana)과 모니터링 EC2 한 대 구성은 그대로다.

| 항목 | 결정 당시 | 현재 | 근거 |
|---|---|---|---|
| 앱 실행 환경 | EC2 ASG | ECS Fargate, `api`(1~3 task) + `worker`(1 task) | `deploy/terraform/ecs.tf` |
| 로그 수집 | 앱 인스턴스의 Alloy | FireLens(Fluent Bit) 사이드카 → Loki | `deploy/ecs/fluent-bit/` |
| 메트릭 수집 | Prometheus가 `ec2_sd_configs`로 scrape | Alloy 사이드카가 `localhost:8081` 수집 → Prometheus remote_write | `deploy/ecs/alloy/config.alloy` |
| 로그 형식 | JSON | logfmt | [../logging/logging.md](../logging/logging.md) |
| Loki 저장소 | S3 | 모니터링 EC2 로컬 디스크(EBS gp3 30GB), 보관 15일 | `deploy/monitoring/loki-config.yml` |
| Prometheus 보관 | - | 15일 | `deploy/monitoring/compose.yml` |
| 모니터링 EC2 | t4g, 월 $30~60 | t4g.medium, CPU 크레딧 unlimited | `deploy/terraform/monitoring.tf` |
| Grafana 접근 | 보안 그룹 제한 | ALB(`grafana.xevbn.click`) + Cognito 인증 | `deploy/terraform/alb.tf`, `cognito.tf` |

**Loki 저장소가 S3가 아니라는 점**은 5장 비용 비교의 B안 전제와 다르다. 현재 로그량에서는 EBS로도 충분하지만, 로그량이 늘면 EBS 용량을 먼저 늘리고 그 이상이면 Loki `object_store`를 S3로 바꾼다. 애플리케이션과 수집기는 바꿀 필요가 없다.

## 10. 후속 작업

| 작업 | 상태 |
|---|---|
| Loki 모놀리식 설정 (S3 스토리지, compactor retention, 로컬 EBS WAL) | 일부: compactor retention(15일)과 로컬 저장은 적용, S3 스토리지는 미적용 |
| 앱 수집기 설치·설정, 모니터링 EC2 Docker Compose 구성 | 완료: ASG 시절 Launch Template의 Alloy → ECS 사이드카(Fluent Bit·Alloy)로 대체. 모니터링 EC2는 `deploy/monitoring/compose.yml` |
| 도메인 메트릭 정의 (예약 성공률, 결제 승인율, outbox lag, 경로 추천 응답 시간) | 일부: 예약 생성 구간 처리 시간만 Prometheus 메트릭(Timer). 주문·결제·환불·예약은 로그 이벤트 기반 Grafana 대시보드 "비즈니스 이벤트"로 본다. outbox lag, 경로 추천 응답 시간은 없음 |
| Grafana 대시보드 및 알림 (결제 실패율, outbox lag) | 일부: 대시보드 5개(애플리케이션 개요, 비즈니스 이벤트, 스케줄러, 로그, RDS·ElastiCache), 알림 6개(api 정상 대상 0, api 메트릭 끊김, worker 스케줄러 멈춤, api 5xx, 결제·환불 최종 실패, ERROR 로그). outbox lag 알림은 없음 |
| 전체 인프라 Terraform 코드화 | 완료 (2026-11-10, [terraform.md](terraform.md)) |
| (선택) OpenTelemetry + Tempo로 분산 트레이싱 | 미진행: traceId는 로그 연결 용도로만 쓴다 |

## 11. 관련 문서
- 운영 배포 구성(모니터링 EC2, 사이드카): [deployment.md](deployment.md)
- 로깅 규칙(로그 형식, 표준 키, Loki 레이블·수집기): [../logging/logging.md](../logging/logging.md)
- 앱 실행 환경 전환: [ecs-migration-rationale.md](ecs-migration-rationale.md)
- 인프라 코드: [terraform.md](terraform.md)
