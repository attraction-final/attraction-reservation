#!/usr/bin/env bash
# Git Bash/Linux/macOS용 실행기. Node.js/Python 없이 Docker와 기존 shell 도구만 사용한다.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/../.." && pwd)"
run_id="${1:-}"
mode="${2:-smoke}"
p95_ms="${3:-}"
load_vus="${TEST_VUS:-10}"
if [[ $# -lt 1 || $# -gt 3 || ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,59}$ \
  || ( "$mode" != smoke && "$mode" != load ) ]]; then
  echo '사용법: bash perf/reservation-list/run.sh RUN_ID [smoke|load] [p95_ms]' >&2
  exit 1
fi
if [[ -n "$p95_ms" && ( "$mode" != load || ! "$p95_ms" =~ ^[1-9][0-9]{0,4}$ ) ]]; then
  echo 'p95_ms는 load에서만 사용할 수 있는 양의 정수입니다.' >&2
  exit 1
fi
if [[ "$load_vus" != 10 && "$load_vus" != 20 ]]; then
  echo 'TEST_VUS는 현재 단계에서 10 또는 20만 지원합니다.' >&2
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
export TEST_MODE="$mode"
export TEST_VUS="$load_vus"
export P95_MS="$p95_ms"
compose() { MSYS_NO_PATHCONV=1 docker compose -f "$compose_file" "$@"; }
snapshot() {
  curl --fail --silent --show-error http://127.0.0.1:18097/actuator/prometheus |
    awk '/^(hikaricp_|jvm_gc_|jvm_memory_used_bytes|process_cpu_usage|process_uptime_seconds|http_server_requests_seconds_)/'
}
sampler_pid=''
stop_sampler() {
  if [[ -n "$sampler_pid" ]]; then
    : > "$target/sampling-stop"
    local sampling_exit=0
    wait "$sampler_pid" || sampling_exit=$?
    printf '%s\n' "$sampling_exit" > "$target/sampling-exit-code.txt"
    if [[ "$sampling_exit" -ne 0 ]]; then echo '자원 수집 프로세스가 비정상 종료됐어요. 결과 해석 시 확인해 주세요.' >&2; fi
    sampler_pid=''
  fi
}

mkdir -p "$script_dir/results"
compose up -d mysql redis app
compose run --rm --no-deps -T accounts
compose run --rm --no-deps -T fixture

# 결과 폴더는 SQL 준비 서비스가 새로 만든다. 이미 있으면 준비 단계에서 중단한다.
{
  echo "startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ "$mode" == smoke ]]; then
    echo 'mode=smoke vus=2 iterationsPerVu=5 expectedListRequests=20'
  else
    echo "mode=load vus=$load_vus warmup=20s measurement=60s gracefulStop=25s p95Ms=${p95_ms:-unset}"
    echo 'model=constant-vus thinkSeconds=1 variants=all,reserved'
    echo 'resourceSampling=enabled targetIntervalSeconds=2 sampledMaxIsNotAnAbsolutePeak=true'
    echo 'p95 criterion is an experimental target, not a production SLO'
  fi
  echo 'users=200 reservations=100000'
  git_head="$(cd -- "$project_dir" && git rev-parse HEAD)"
  echo "gitHead=$git_head"
  (cd -- "$project_dir" && git status --short)
  sha256sum "$jar" "$script_dir/compose.yaml" "$script_dir/prepare.sql" \
    "$script_dir/expected.sql" "$script_dir/reservation-list.js" "$script_dir/auth.js" \
    "$script_dir/prepare-accounts.js" "$script_dir/run.sh" "$script_dir/prepare.sh" \
    "$script_dir/sample-resources.sh"
  compose exec -T mysql mysql -uperf -pperf-local-only --batch --skip-column-names \
    reservation_perf -e 'SELECT VERSION(); SELECT COUNT(*) FROM reservations; SHOW INDEX FROM reservations;'
  compose ps --format '{{.Service}} {{.Image}} {{.State}}'
  MSYS_NO_PATHCONV=1 docker info --format 'dockerNcpu={{.NCPU}} dockerMemoryBytes={{.MemTotal}}'
  for service in app mysql redis; do
    container_id="$(compose ps -q "$service" | tr -d '\r')"
    MSYS_NO_PATHCONV=1 docker inspect --format '{{.Name}} {{.Image}}' "$container_id"
  done
} > "$target/metadata.txt"

if [[ "$mode" == load ]]; then
  echo "warm-up: 서로 다른 회원 ${load_vus}명으로 20초. 다음 측정과 별도 k6 프로세스입니다."
  echo "warmupStartedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/metadata.txt"
  if compose run --rm --no-deps -T -e TEST_MODE=warmup k6; then
    echo 0 > "$target/warmup-exit-code.txt"
  else
    exit_code=$?
    printf '%s\n' "$exit_code" > "$target/warmup-exit-code.txt"
    echo 'warm-up 기능 검증 실패. 측정은 시작하지 않습니다.' >&2
    exit "$exit_code"
  fi
fi
# load의 before snapshot은 warm-up 종료 후에 기록한다.
snapshot > "$target/metrics-before.txt"

echo "measurementStartedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/metadata.txt"
measurement_container="reservation-perf-$run_id"
if [[ "$mode" == load ]]; then
  trap stop_sampler EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  bash "$script_dir/sample-resources.sh" "$target" "$compose_file" "$measurement_container" &
  sampler_pid=$!
fi
echo "$mode 측정 시작"
if compose run --rm --no-deps -T --name "$measurement_container" k6; then
  exit_code=0
else
  exit_code=$?
fi
stop_sampler
echo "measurementFinishedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/metadata.txt"
if ! snapshot > "$target/metrics-after.txt"; then
  echo '메트릭 수집 실패. 앱 관리 포트를 확인하세요.' > "$target/metrics-after.txt"
fi
printf '%s\n' "$exit_code" > "$target/exit-code.txt"
echo "결과: $target / 종료 코드: $exit_code"
exit "$exit_code"
