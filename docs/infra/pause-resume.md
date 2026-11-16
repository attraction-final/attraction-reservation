# 인프라 꺼 두기와 다시 켜기

비용 때문에 운영 인프라를 잠시 꺼 둘 때 쓰는 절차다. Terraform 변수 `paused` 하나로 시간당 비용이 드는 리소스만 내리고, 비용이 없거나 거의 없는 리소스는 그대로 둔다. 같은 계정·같은 리전에 다시 켜는 것만 다룬다.

## 1. 대상

| 처리 | 리소스 | 다시 켤 때 |
|---|---|---|
| 삭제 | NAT Gateway + EIP | 새로 만든다. **NAT 공인 IP가 바뀐다** |
| 삭제 | ALB, 80·443 리스너, grafana 규칙, `api`·`grafana` A 레코드 | 새로 만든다. ALB DNS 이름이 바뀌지만 alias 레코드가 따라간다 |
| 삭제 | ECS 서비스 api·worker, api 오토스케일링, 5xx 롤백 알람 | 새로 만든다. task definition은 남아 있어 패밀리의 최신 리비전으로 뜬다 |
| 삭제 (최종 스냅샷) | RDS `attraction-final-db` | 같은 identifier로 스냅샷에서 복원한다. 엔드포인트와 비밀번호가 그대로다 |
| 삭제 (스냅샷 없음) | ElastiCache `attraction-final-cache` | 새로 만든다. 데이터가 사라진다 |
| 중지 | 모니터링 EC2 | 시작한다. 루트 볼륨(`/data`)과 사설 IP `10.0.130.10`이 그대로다 |
| 유지 | VPC, 서브넷, 라우팅 테이블, SG, 대상 그룹, IAM, ECR, S3, Route 53 존, ACM, Cognito, ECS 클러스터, SSM 파라미터, 로그 그룹 | - |

- Redis에는 캐시, 리프레시 토큰, 소셜 로그인 코드(60초)만 있다. 다시 켜면 사용자는 다시 로그인한다.
- RDS를 지우면 자동 백업도 함께 지워진다. 최종 스냅샷 하나만 남고 시점 복구(PITR)는 할 수 없다.
- 꺼 둔 동안 private 라우팅 테이블에는 지운 NAT를 가리키는 경로가 blackhole로 남는다. 비용이 없고, 다시 켜면 새 NAT로 바뀐다.
- 꺼 둔 동안 남는 비용은 모니터링 EC2 볼륨(30GB), RDS 스냅샷, ECR 이미지, Route 53 존 정도다.

## 2. 꺼 두기

삭제 보호와 최종 스냅샷 이름은 RDS를 지울 때 **state에 있는 값**을 쓴다. 그래서 두 번에 나눠 apply한다.

**0. CD를 끈다.** 꺼 둔 동안 main에 push하면 서비스가 없어 배포가 실패한다.

```bash
gh workflow disable CD
```

**1. 삭제 보호를 끄고 최종 스냅샷 이름을 정한다.** `deploy/terraform/paused.auto.tfvars`를 만든다. 스냅샷 이름에는 날짜를 넣는다. 같은 이름의 스냅샷이 있으면 삭제가 실패한다.

```hcl
db_deletion_protection       = false
db_final_snapshot_identifier = "attraction-final-db-paused-20261116"
```

```bash
cd deploy/terraform
terraform plan   # aws_db_instance.main[0]의 in-place 변경 2개만 있어야 한다
terraform apply
```

**2. 리소스를 내린다.** 같은 파일에 `paused = true`를 추가한다.

```hcl
paused                       = true
db_deletion_protection       = false
db_final_snapshot_identifier = "attraction-final-db-paused-20261116"
```

```bash
terraform plan   # 15 to destroy, 그리고 aws_ec2_instance_state.monitoring의 state가 stopped
terraform apply  # RDS 최종 스냅샷 때문에 10분 이상 걸린다
```

**3. `paused.auto.tfvars`를 커밋한다.** 이 파일이 있는 동안에는 누가 `terraform apply`를 실행해도 꺼진 상태가 유지된다. 파일 없이 apply하면 다시 켜려고 하는데, RDS를 새로 만들 비밀번호가 없어 실패한다. (`deploy/terraform/` 변경은 CD 배포 대상이 아니다)

**4. 확인한다.**

```bash
aws rds describe-db-snapshots --db-snapshot-identifier attraction-final-db-paused-20261116 \
  --query 'DBSnapshots[0].Status'   # available
```

## 3. 다시 켜기

**1. `paused.auto.tfvars`를 지우고 스냅샷 이름을 넘겨 apply한다.**

```bash
cd deploy/terraform
rm paused.auto.tfvars
terraform plan  -var db_snapshot_identifier=attraction-final-db-paused-20261116
terraform apply -var db_snapshot_identifier=attraction-final-db-paused-20261116
```

- RDS 복원과 ElastiCache 생성에 각각 10~20분이 걸린다.
- ECS 서비스는 NAT 경로, RDS, Redis, 모니터링 EC2가 준비된 뒤에 만들어진다(`depends_on`). 먼저 뜨면 기동에 실패해 서킷 브레이커가 걸린다.
- `db_snapshot_identifier`는 생성할 때만 쓰인다. 이후 apply에서는 넘기지 않아도 된다(`ignore_changes`).
- 삭제 보호는 기본값(`true`)으로 돌아간다.

**2. 확인한다.**

```bash
terraform output redis_primary_endpoint
aws ssm get-parameter --name /attraction-reservation/prod/env/REDIS_HOST --with-decryption \
  --query Parameter.Value --output text
```

- 두 값이 다르면 SSM `env/REDIS_HOST`를 새 엔드포인트로 바꾸고 api·worker 서비스를 `--force-new-deployment`로 다시 띄운다.
- `https://api.xevbn.click/readyz`가 200인지, `https://grafana.xevbn.click`에 들어가지는지, Grafana에 앱 로그가 들어오는지 본다.
- 외부 서비스에 NAT 공인 IP를 등록해 둔 곳이 있으면 새 IP(`aws ec2 describe-nat-gateways`)로 바꾼다.

**3. CD를 다시 켜고, 남은 스냅샷을 정리한다.**

```bash
gh workflow enable CD
aws rds delete-db-snapshot --db-snapshot-identifier attraction-final-db-paused-20261116
```

- 꺼 둔 동안 main에 머지된 커밋은 배포되지 않았다. `APP_VERSION`을 바꾸지 않았으므로 다음 main push의 CD가 변경을 감지해 함께 배포한다.
- 스냅샷은 복원이 확인된 뒤에 지운다.

## 4. 검증 상태

2026-11-16 기준 plan으로만 확인했고, 실제로 내리고 켜 본 적은 없다.

- `paused=false`: 기존 리소스 15개가 `[0]` 주소로 옮겨지고, `aws_ec2_instance_state.monitoring` 1개가 추가된다(실행 중 상태를 state에 기록할 뿐 인스턴스는 바뀌지 않는다).
- 꺼 두기 1단계: RDS in-place 변경 2개(`deletion_protection`, `final_snapshot_identifier`)
- 꺼 두기 2단계: 15개 삭제, 모니터링 EC2 중지

처음 실제로 실행할 때 확인할 것:

- [ ] RDS 스냅샷 복원 뒤 설정(백업 보존, 삭제 보호, 서브넷 그룹)이 코드와 같은지 (`terraform plan`이 `No changes`인지)
- [ ] ElastiCache 엔드포인트가 그대로인지
- [ ] ECS 서비스가 서킷 브레이커 없이 처음 배포에 성공하는지
- [ ] Grafana 대상 그룹에 모니터링 EC2가 healthy로 돌아오는지
