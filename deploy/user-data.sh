#!/bin/bash
# 운영 ASG Launch Template의 user-data. 인스턴스 첫 기동 때 한 번 실행된다.
# 실행 결과는 인스턴스의 /var/log/cloud-init-output.log 에서 확인한다.
#
# 기동 흐름
#   1. Docker와 compose 플러그인을 설치한다.
#   2. Parameter Store의 APP_VERSION을 읽고, 그 버전의 실행 구성(compose, Alloy 설정)을 S3에서 받는다.
#   3. Parameter Store의 env/ 아래 값으로 app.env를 만든다. (이름 그대로 환경변수가 된다)
#   4. ECR에 로그인하고 docker compose로 애플리케이션과 Alloy를 실행한다.
#
# 전제
#   - AMI: Amazon Linux 2023 (aws cli v2 기본 포함)
#   - 인스턴스 역할: ECR pull, S3 GetObject(ARTIFACT_BUCKET/ARTIFACT_PREFIX/*),
#     SSM GetParameter·GetParametersByPath(SSM_PREFIX/*), SecureString 복호화
#   - Parameter Store
#       ${SSM_PREFIX}/APP_VERSION   배포할 커밋 SHA. CD가 갱신한다.
#       ${SSM_PREFIX}/LOKI_URL      Alloy가 로그를 보낼 Loki push 주소
#       ${SSM_PREFIX}/PROMETHEUS_URL  Alloy가 메트릭을 보낼 Prometheus remote_write 주소
#       ${SSM_PREFIX}/env/*         애플리케이션 환경변수 (DB, JWT, PortOne 등)
#
# 비밀값이 출력되지 않도록 set -x를 쓰지 않는다.

set -euo pipefail

SSM_PREFIX=/attraction-reservation/prod
ARTIFACT_BUCKET=attraction-reservation-793168138824-ap-northeast-2-an
ARTIFACT_PREFIX=attraction-reservation
ECR_REPOSITORY=attraction-reservation
APP_ENV=prod
COMPOSE_VERSION=v2.40.3   # compose.yml의 env_file format: raw 에 2.30 이상이 필요하다
DEPLOY_DIR=/opt/attraction-reservation

# IMDSv2로 리전, 계정, 인스턴스 ID를 읽는다.
IMDS_TOKEN=$(curl -sf -X PUT http://169.254.169.254/latest/api/token \
  -H 'X-aws-ec2-metadata-token-ttl-seconds: 300')
IDENTITY=$(curl -sf -H "X-aws-ec2-metadata-token: ${IMDS_TOKEN}" \
  http://169.254.169.254/latest/dynamic/instance-identity/document)
AWS_REGION=$(echo "$IDENTITY" | sed -n 's/.*"region" *: *"\([^"]*\)".*/\1/p')
ACCOUNT_ID=$(echo "$IDENTITY" | sed -n 's/.*"accountId" *: *"\([^"]*\)".*/\1/p')
INSTANCE_ID=$(echo "$IDENTITY" | sed -n 's/.*"instanceId" *: *"\([^"]*\)".*/\1/p')
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

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

# 2) 배포 버전과 실행 구성
APP_VERSION=$(aws ssm get-parameter --name "${SSM_PREFIX}/APP_VERSION" \
  --query 'Parameter.Value' --output text)
if ! [[ "$APP_VERSION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "APP_VERSION이 40자 커밋 SHA가 아닙니다: ${APP_VERSION}" >&2
  exit 1
fi
LOKI_URL=$(aws ssm get-parameter --name "${SSM_PREFIX}/LOKI_URL" \
  --query 'Parameter.Value' --output text)
PROMETHEUS_URL=$(aws ssm get-parameter --name "${SSM_PREFIX}/PROMETHEUS_URL" \
  --query 'Parameter.Value' --output text)

mkdir -p "$DEPLOY_DIR"
aws s3 cp --recursive "s3://${ARTIFACT_BUCKET}/${ARTIFACT_PREFIX}/${APP_VERSION}/" "$DEPLOY_DIR/"
cd "$DEPLOY_DIR"

# compose 변수 치환용
cat > .env <<EOF
APP_IMAGE=${REGISTRY}/${ECR_REPOSITORY}
APP_VERSION=${APP_VERSION}
APP_ENV=${APP_ENV}
LOKI_URL=${LOKI_URL}
PROMETHEUS_URL=${PROMETHEUS_URL}
INSTANCE_ID=${INSTANCE_ID}
EOF

# 3) 애플리케이션 환경변수. 파라미터 이름의 마지막 부분이 변수 이름이 된다.
#    값에 탭·개행이 들어가는 파라미터는 지원하지 않는다.
#    compose.yml이 format: raw로 읽으므로 값은 따옴표·이스케이프 없이 그대로 쓴다.
umask 077
aws ssm get-parameters-by-path --path "${SSM_PREFIX}/env/" --with-decryption \
  --query 'Parameters[].[Name,Value]' --output text \
  | while IFS=$'\t' read -r NAME VALUE; do
      printf '%s=%s\n' "${NAME##*/}" "$VALUE"
    done > app.env
umask 022
if [ ! -s app.env ]; then
  echo "${SSM_PREFIX}/env/ 아래에 파라미터가 없습니다." >&2
  exit 1
fi

# 4) 실행
aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
docker compose up -d

echo "attraction-reservation ${APP_VERSION} 기동 완료"
