#!/usr/bin/env bash
# Git Bash/Linux/macOS용 실행기. Node.js/Python 없이 Docker와 기존 shell 도구만 사용한다.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/../.." && pwd)"
run_id="${1:-}"
if [[ $# -ne 1 || ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,59}$ ]]; then
  echo '사용법: bash perf/reservation-list/run.sh smoke-02' >&2
  exit 1
fi
target="$script_dir/results/$run_id"
if [[ -e "$target" ]]; then
  echo '이미 존재하는 RUN_ID입니다. 다른 이름을 사용하세요.' >&2
  exit 1
fi
jar="$project_dir/build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar"
if [[ ! -f "$jar" ]]; then
  echo '앱 jar가 없습니다. 프로젝트 루트에서 ./gradlew bootJar를 먼저 실행하세요.' >&2
  exit 1
fi

# Docker에만 경로 변환 예외를 적용한다. Git 등 host 명령에는 적용하지 않는다.
compose_file="$script_dir/compose.yaml"
case "$(uname -s)" in
  MINGW*|MSYS*) compose_file="$(cygpath -w "$compose_file")" ;;
esac
export RUN_ID="$run_id"
export PERF_UID="$(id -u)"
export PERF_GID="$(id -g)"
compose() { MSYS_NO_PATHCONV=1 docker compose -f "$compose_file" "$@"; }
snapshot() {
  curl --fail --silent --show-error http://127.0.0.1:18097/actuator/prometheus |
    awk '/^(hikaricp_|jvm_gc_|jvm_memory_used_bytes|process_cpu_usage|process_uptime_seconds|http_server_requests_seconds_)/'
}

mkdir -p "$script_dir/results"
compose up -d mysql redis app
compose run --rm --no-deps -T accounts
compose run --rm --no-deps -T fixture

# 결과 폴더는 SQL 준비 서비스가 새로 만든다. 이미 있으면 준비 단계에서 중단한다.
{
  echo "startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo 'mode=smoke vus=2 iterationsPerVu=5 expectedListRequests=20'
  echo 'users=200 reservations=100000'
  git_head="$(cd -- "$project_dir" && git rev-parse HEAD)"
  echo "gitHead=$git_head"
  (cd -- "$project_dir" && git status --short)
  sha256sum "$jar" "$script_dir/compose.yaml" "$script_dir/prepare.sql" \
    "$script_dir/expected.sql" "$script_dir/reservation-list.js" "$script_dir/auth.js" \
    "$script_dir/prepare-accounts.js" "$script_dir/run.sh" "$script_dir/prepare.sh"
  compose exec -T mysql mysql -uperf -pperf-local-only --batch --skip-column-names \
    reservation_perf -e 'SELECT VERSION(); SELECT COUNT(*) FROM reservations; SHOW INDEX FROM reservations;'
  compose ps --format '{{.Service}} {{.Image}} {{.State}}'
  for service in app mysql redis; do
    container_id="$(compose ps -q "$service" | tr -d '\r')"
    MSYS_NO_PATHCONV=1 docker inspect --format '{{.Name}} {{.Image}}' "$container_id"
  done
} > "$target/metadata.txt"
snapshot > "$target/metrics-before.txt"

echo 'smoke 실행: 서로 다른 회원 2명, 목록 조회 총 20건'
if compose run --rm --no-deps -T k6; then
  exit_code=0
else
  exit_code=$?
fi
if ! snapshot > "$target/metrics-after.txt"; then
  echo '메트릭 수집 실패. 앱 관리 포트를 확인하세요.' > "$target/metrics-after.txt"
fi
printf '%s\n' "$exit_code" > "$target/exit-code.txt"
echo "결과: $target / 종료 코드: $exit_code"
exit "$exit_code"
