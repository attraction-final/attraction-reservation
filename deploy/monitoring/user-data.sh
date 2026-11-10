#!/bin/bash
# 모니터링 인스턴스의 user-data. 인스턴스 첫 기동 때 한 번 실행된다.
# 실행 결과는 인스턴스의 /var/log/cloud-init-output.log 에서 확인한다.
#
# 기동 흐름
#   1. Docker와 compose 플러그인을 설치한다.
#   2. Parameter Store의 MONITORING_VERSION에 해당하는 실행 구성을 S3의 monitoring/<SHA>/ 에서 받는다.
#   3. 데이터 디렉터리(/data)를 만들고 컨테이너 사용자에 맞춰 소유자를 바꾼다.
#   4. Grafana 관리자 비밀번호로 .env를 만들고 docker compose로 실행한다.
#
# 인스턴스를 띄울 때 지정할 값
#   - AMI: Amazon Linux 2023 arm64 (aws cli v2 기본 포함), 타입 t4g.medium
#   - 사설 IP 10.0.130.10 고정 (앱의 LOKI_URL, PROMETHEUS_URL이 이 주소를 가리킨다)
#   - 태그 Name=attraction-reservation-monitoring (CD가 이 태그로 인스턴스를 찾는다)
#   - 인스턴스 프로파일 monitoring-ec2-role: S3 배포 번들 읽기·목록, SSM 파라미터(MONITORING_VERSION,
#     monitoring/*) 읽기, CloudWatch 지표 읽기, SSM 접속
#   - IMDSv2 필수, hop limit 2: Grafana 컨테이너가 인스턴스 역할로 CloudWatch를 읽는다.
#   - 루트 볼륨에 /data 를 둔다. 인스턴스를 지워도 볼륨이 남도록 DeleteOnTermination=false로 띄운다.
#
# 필요한 Parameter Store 값
#   ${SSM_PREFIX}/MONITORING_VERSION                 실행 구성을 받을 커밋 SHA. CD가 갱신한다.
#     주의: 새 환경에서 첫 모니터링 배포가 실패하면(인스턴스가 아직 없을 때 등) CD가 이 값을 none으로 남긴다.
#           이 상태로는 인스턴스가 기동하지 못하므로, S3 monitoring/<SHA>/ 에 번들이 있는 SHA로 직접 고친 뒤 띄운다.
#   ${SSM_PREFIX}/monitoring/GRAFANA_ADMIN_PASSWORD  Grafana admin 비밀번호
#
# 이후 설정 변경은 CD(deploy-monitoring)가 SSM Run Command로 반영한다.
#
# 비밀값이 출력되지 않도록 set -x를 쓰지 않는다.

set -euo pipefail

SSM_PREFIX=/attraction-reservation/prod
ARTIFACT_BUCKET=attraction-reservation-793168138824-ap-northeast-2-an
ARTIFACT_PREFIX=attraction-reservation
COMPOSE_VERSION=v2.40.3
DEPLOY_DIR=/opt/monitoring

# IMDSv2로 리전을 읽는다.
IMDS_TOKEN=$(curl -sf -X PUT http://169.254.169.254/latest/api/token \
  -H 'X-aws-ec2-metadata-token-ttl-seconds: 300')
AWS_REGION=$(curl -sf -H "X-aws-ec2-metadata-token: ${IMDS_TOKEN}" \
  http://169.254.169.254/latest/meta-data/placement/region)
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"

# 1) Docker, compose 플러그인
dnf install -y docker
systemctl enable --now docker

COMPOSE_DIR=/usr/local/lib/docker/cli-plugins
COMPOSE_URL="https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-$(uname -m)"
mkdir -p "$COMPOSE_DIR"
curl -sfL "$COMPOSE_URL" -o "${COMPOSE_DIR}/docker-compose"
curl -sfL "${COMPOSE_URL}.sha256" -o /tmp/docker-compose.sha256
echo "$(cut -d' ' -f1 /tmp/docker-compose.sha256)  ${COMPOSE_DIR}/docker-compose" | sha256sum -c -
chmod +x "${COMPOSE_DIR}/docker-compose"

# 2) 실행 구성
MONITORING_VERSION=$(aws ssm get-parameter --name "${SSM_PREFIX}/MONITORING_VERSION" \
  --query 'Parameter.Value' --output text)
if ! [[ "$MONITORING_VERSION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "MONITORING_VERSION이 40자 커밋 SHA가 아닙니다: ${MONITORING_VERSION}" >&2
  exit 1
fi

mkdir -p "$DEPLOY_DIR"
aws s3 cp --recursive "s3://${ARTIFACT_BUCKET}/${ARTIFACT_PREFIX}/monitoring/${MONITORING_VERSION}/" "$DEPLOY_DIR/"
cd "$DEPLOY_DIR"

# 3) 데이터 디렉터리. 각 이미지의 기본 실행 사용자(UID)에 맞춘다.
mkdir -p /data/prometheus /data/loki /data/grafana
chown -R 65534:65534 /data/prometheus   # prom/prometheus: nobody
chown -R 10001:10001 /data/loki         # grafana/loki
chown -R 472:0 /data/grafana            # grafana/grafana

# 4) 실행
umask 077
GRAFANA_ADMIN_PASSWORD=$(aws ssm get-parameter --name "${SSM_PREFIX}/monitoring/GRAFANA_ADMIN_PASSWORD" \
  --with-decryption --query 'Parameter.Value' --output text)
# 작은따옴표로 감싸야 Compose가 $, # 를 해석하지 않는다. 감쌀 수 없는 작은따옴표는 거부한다.
case "$GRAFANA_ADMIN_PASSWORD" in
  *\'*)
    echo "GRAFANA_ADMIN_PASSWORD에 작은따옴표가 있어 .env에 쓸 수 없습니다." >&2
    exit 1
    ;;
esac
printf "GRAFANA_ADMIN_PASSWORD='%s'\n" "$GRAFANA_ADMIN_PASSWORD" > .env
umask 022

docker compose up -d

echo "monitoring ${MONITORING_VERSION} 기동 완료"
