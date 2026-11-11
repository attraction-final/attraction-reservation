# Terraform

운영 AWS 인프라를 [`deploy/terraform/`](../../deploy/terraform)에서 관리한다. 처음에는 콘솔·CLI로 만들었고, 2026-11-10에 기존 리소스를 `import` 블록으로 state에 가져왔다(KAN-151). 이때 리소스를 다시 만들지 않았다.

- 계정 `793168138824`, 리전 `ap-northeast-2`, 환경은 운영 하나다. 모듈 없이 루트 하나로 구성한다.
- Terraform `>= 1.10`, AWS provider `~> 6.0` (`.terraform.lock.hcl`로 버전 고정)

## 1. 관리 범위

| 파일 | 리소스 |
|---|---|
| `network.tf` | VPC, 서브넷 4개, IGW, NAT(2a 하나) + EIP, 라우팅 테이블, S3 게이트웨이 엔드포인트 |
| `dns.tf` | 퍼블릭 존 `xevbn.click`(`api`, `grafana`, ACM 검증 레코드), 프라이빗 존 `attraction.internal`(`loki`). ACM 인증서는 data source로 읽기만 한다 |
| `security-groups.tf` | SG 5개와 규칙. 규칙은 SG를 서로 참조하므로 inline이 아닌 별도 리소스로 둔다 |
| `alb.tf` | ALB, 80·443 리스너, grafana 규칙(Cognito 인증), TG `attraction-app-ecs`·`attraction-grafana` |
| `cognito.tf` | Grafana 앞단 인증용 user pool, client, domain |
| `ecs.tf` | 클러스터, 서비스 api·worker, 사이드카 로그 그룹, api 오토스케일링(CPU 60% 기준 1~3개) |
| `data-stores.tf` | RDS, ElastiCache, 서브넷 그룹 |
| `monitoring.tf` | 모니터링 EC2, 인스턴스 프로파일 |
| `iam.tf` | GitHub OIDC provider, ECS execution·task 역할, `github-actions-role`, `monitoring-ec2-role` |
| `ecr.tf` | 저장소 3개, lifecycle 정책(태그된 이미지 최근 30개 유지) |
| `s3.tf` | CD 아티팩트 버킷 |
| `imports.tf` | 처음 가져올 때 쓴 import 블록. 이미 state에 있으면 아무 일도 하지 않는다 |

### 관리하지 않는 것

| 대상 | 이유 |
|---|---|
| ECS task definition | CD가 `deploy/ecs/*-task-definition.json`으로 등록하고 서비스를 갱신한다. 서비스의 `task_definition`은 `ignore_changes`로 둔다. api의 `desired_count`도 오토스케일링이 바꾸므로 `ignore_changes`로 둔다 |
| SSM 파라미터 값 | 비밀값이 state에 평문으로 남고, `APP_VERSION` 등은 CD가 계속 바꾼다 |
| RDS 비밀번호 | state에 남기지 않는다. 설정에 적지 않았다 |
| 모니터링 EC2의 `ami`, `user_data` | 바뀌면 인스턴스를 다시 만들게 된다. `ignore_changes`로 두고, 기동 스크립트 원본은 `deploy/monitoring/user-data.sh`다 |
| 모니터링 EC2를 TG `attraction-grafana`에 등록한 것 | 콘솔에서 등록했다 |
| EC2(ASG) 시절 리소스 | ASG, Launch Template, TG `attraction-final`, `ec2-role` 등은 ECS 전환 7단계에서 지운다 |
| ECR 저장소 `alloy` | 출처를 확인 중이고 CD가 쓰지 않는다 |
| ALB가 자동으로 붙인 EIP 2개, default SG·NACL·main 라우팅 테이블 | AWS가 관리하거나 기본값이다 |
| GitHub 설정 (Actions 변수, Environment) | 범위 밖이다 |

## 2. state

- S3 버킷 `attraction-reservation-tfstate-793168138824`, key `prod/terraform.tfstate`
- 잠금은 S3 lock 파일(`use_lockfile`)로 한다. DynamoDB는 쓰지 않는다.
- 버킷은 Terraform 밖에서 CLI로 만들었다(버전 관리, SSE-S3, 퍼블릭 차단, TLS 강제). 자기 state를 담는 버킷을 같은 state로 관리하지 않기 위해서다.
- state에는 리소스 속성이 들어 있으므로 버킷 접근 권한을 넓히지 않는다.

## 3. 사용법

```bash
cd deploy/terraform
terraform init
terraform plan            # 변경 사항 확인
terraform apply           # plan을 확인한 뒤 적용
```

- 인프라는 이 코드를 고쳐서 바꾼다. 콘솔에서 바꾸면 다음 plan에 되돌리는 변경으로 나온다.
- 콘솔에서 급하게 바꿨다면 바로 코드에 같은 값을 반영하고 plan이 `No changes`인지 확인한다.
- `deploy/terraform/` 변경은 CD의 앱 배포 대상에서 빠져 있다. 코드를 머지해도 자동으로 적용되지 않으므로 plan과 apply는 직접 실행한다.
- 적용 전에는 plan에 `destroy`나 `must be replaced`가 없는지 확인한다. RDS, ElastiCache, 모니터링 EC2는 다시 만들면 데이터가 사라진다.

## 4. 주의할 점

- RDS는 `apply_immediately = true`라서 apply하면 maintenance window를 기다리지 않고 바로 반영된다. 재시작이 필요한 변경은 plan을 보고 시간을 골라 적용한다.
- RDS는 자동 마이너 업그레이드가 켜져 있다. AWS가 버전을 올리면 `engine_version` diff가 생기므로 코드의 버전을 실제 값으로 고친다.
- ALB 리스너·규칙의 forward 액션은 import 결과에 맞춰 적었다. 형식을 바꾸면 의미가 같아도 diff가 생길 수 있다.
- ECR lifecycle 정책은 태그된 이미지만 센다. buildx로 올린 이미지는 태그된 index 아래 플랫폼 이미지가 태그 없이 들어 있어서, 태그 없는 이미지를 지우는 규칙을 두면 실행 중인 이미지가 깨질 수 있다.
- api task 수는 오토스케일링 대상(`aws_appautoscaling_target.api`)의 min/max로 바꾼다. 서비스의 `desired_count`는 `ignore_changes`라서 고쳐도 반영되지 않는다. worker는 스케줄러가 겹치면 안 되므로 1개로 두고 오토스케일링을 붙이지 않는다.
- api 프로필(`application-api.yml`)은 스케줄러를 모두 끈다. 스케줄러를 추가하면 여기에도 꺼야 task가 늘어날 때 중복 실행되지 않는다.
- SG 이름과 설명은 EC2 시절 그대로다(`server-instance-sg` 등). 설명을 바꾸면 SG를 다시 만들어야 한다.

## 5. 가져오면서 발견한 것 (바꾸지 않고 현재 값을 그대로 옮김)

- [x] RDS가 public 서브넷에 있다. → 2026-11-10 private 서브넷 그룹 `attraction-final-db-private-sng`(private 2a/2b)로 옮겼다. 같은 VPC 안에서는 서브넷 그룹을 바꿀 수 없어서 스냅샷을 같은 identifier로 복원했다(엔드포인트 동일).
- [x] RDS 자동 백업이 꺼져 있다. → 2026-11-10 백업 보존 7일, 삭제 방지, 삭제 시 final snapshot(`attraction-final-db-final`)을 켰다.
- [ ] Terraform을 루트 계정 자격 증명으로 실행했다. 관리자 IAM 사용자나 IAM Identity Center로 바꾼다.
