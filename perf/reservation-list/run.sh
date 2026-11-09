#!/usr/bin/env bash
# Git Bash/Linux/macOS용 실행기. Node.js/Python 없이 Docker와 기존 shell 도구만 사용한다.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/../.." && pwd)"
run_id="${1:-}"
mode="${2:-smoke}"
p95_ms="${3:-}"
load_vus="${TEST_VUS:-10}"
start_mode="${START_MODE:-simultaneous}"
warmup_seconds="${WARMUP_SECONDS:-20}"
app_cpus="${TEST_APP_CPUS:-1.0}"
pool_size="${TEST_POOL_SIZE:-10}"
pool_comparison=0
if [[ -n "${TEST_POOL_SIZE:-}" ]]; then pool_comparison=1; fi
if [[ $# -lt 1 || $# -gt 3 || ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,59}$ \
  || ( "$mode" != smoke && "$mode" != load ) ]]; then
  echo '사용법: bash perf/reservation-list/run.sh RUN_ID [smoke|load] [p95_ms]' >&2
  exit 1
fi
if [[ -n "$p95_ms" && ( "$mode" != load || ! "$p95_ms" =~ ^[1-9][0-9]{0,4}$ ) ]]; then
  echo 'p95_ms는 load에서만 사용할 수 있는 양의 정수입니다.' >&2
  exit 1
fi
if [[ "$load_vus" != 10 && "$load_vus" != 20 && "$load_vus" != 50 && "$load_vus" != 100 ]]; then
  echo 'TEST_VUS는 현재 단계에서 10, 20, 50, 100만 지원합니다.' >&2
  exit 1
fi
if [[ "$start_mode" != simultaneous && "$start_mode" != staggered ]]; then
  echo 'START_MODE는 simultaneous 또는 staggered여야 합니다.' >&2
  exit 1
fi
if [[ "$mode" != load && "$start_mode" != simultaneous ]]; then
  echo '분산 시작은 load에서만 지원합니다.' >&2
  exit 1
fi
if [[ ! "$warmup_seconds" =~ ^[1-9][0-9]{0,2}$ ]] || (( warmup_seconds > 600 )); then
  echo 'WARMUP_SECONDS는 1~600 사이의 정수(초)여야 합니다.' >&2
  exit 1
fi
if [[ "$pool_size" != 10 && "$pool_size" != 20 ]]; then
  echo 'TEST_POOL_SIZE는 현재 비교 단계에서 10 또는 20만 지원합니다.' >&2
  exit 1
fi
if [[ "$pool_comparison" == 1 && ( "$mode" != load || "$load_vus" != 100 || "$start_mode" != simultaneous ) ]]; then
  echo 'pool 비교는 100 VUs·동시 시작 load로 조건을 고정합니다.' >&2
  exit 1
fi
if [[ "$app_cpus" != 1 && "$app_cpus" != 1.0 && "$app_cpus" != 2 && "$app_cpus" != 2.0 ]]; then
  echo 'TEST_APP_CPUS는 현재 비교 단계에서 1 또는 2만 지원합니다.' >&2
  exit 1
fi
if [[ -n "${TEST_APP_CPUS:-}" && "$pool_comparison" != 1 ]]; then
  echo 'CPU 비교에는 TEST_POOL_SIZE를 명시해 앱을 재생성해야 합니다.' >&2
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
export START_MODE="$start_mode"
export WARMUP_SECONDS="$warmup_seconds"
export TEST_APP_CPUS="$app_cpus"
export TEST_POOL_SIZE="$pool_size"
export P95_MS="$p95_ms"
compose() { MSYS_NO_PATHCONV=1 docker compose -f "$compose_file" "$@"; }
snapshot() {
  curl --fail --silent --show-error http://127.0.0.1:18097/actuator/prometheus |
    awk '/^(hikaricp_|jvm_gc_|jvm_memory_used_bytes|process_cpu_usage|process_uptime_seconds|http_server_requests_seconds_)/'
}
sampler_pid=''
measurement_container="reservation-perf-$run_id"
measurement_owner="$run_id-$$"
measurement_started=false
measurement_cleaned=false
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
cleanup_measurement() {
  stop_sampler
  if [[ "$measurement_started" != true || "$measurement_cleaned" == true ]]; then return 0; fi
  measurement_cleaned=true
  local cleanup_exit=0
  local identity
  identity="$(MSYS_NO_PATHCONV=1 docker inspect --format \
    '{{index .Config.Labels "kan134.owner"}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' \
    "$measurement_container" 2>> "$target/cleanup-errors.txt" | tr -d '\r')" || cleanup_exit=1
  # 이번 실행의 label과 Compose 소속이 일치하는 측정용 k6만 정리한다.
  if [[ "$identity" != "$measurement_owner|attraction-reservation-perf|k6" ]]; then
    echo '측정 컨테이너 소유 확인 실패. 컨테이너를 임의로 제거하지 않습니다.' >> "$target/cleanup-errors.txt"
    cleanup_exit=1
  else
    local running
    running="$(MSYS_NO_PATHCONV=1 docker inspect --format '{{.State.Running}}' \
      "$measurement_container" 2>> "$target/cleanup-errors.txt" | tr -d '\r')" || cleanup_exit=1
    if [[ "$running" == true ]]; then
      # 사용자 중단 시에도 이번 테스트의 k6만 종료한다. 앱·DB·Redis에는 적용하지 않는다.
      MSYS_NO_PATHCONV=1 docker stop --timeout 5 "$measurement_container" \
        >/dev/null 2>> "$target/cleanup-errors.txt" || cleanup_exit=1
    fi
    MSYS_NO_PATHCONV=1 docker rm "$measurement_container" \
      >/dev/null 2>> "$target/cleanup-errors.txt" || cleanup_exit=1
  fi
  printf '%s\n' "$cleanup_exit" > "$target/cleanup-exit-code.txt"
  if [[ "$cleanup_exit" -ne 0 ]]; then echo '측정 컨테이너 정리 상태를 cleanup-errors.txt에서 확인해 주세요.' >&2; fi
  return 0
}

mkdir -p "$script_dir/results"
if [[ "$pool_comparison" == 1 ]]; then
  # A/B 모두 같은 준비 절차를 거치도록 전용 앱만 재생성한다. DB와 Redis의 데이터는 유지한다.
  compose up -d --wait --wait-timeout 120 mysql redis
  compose up -d --force-recreate --no-deps app
else
  compose up -d mysql redis app
fi
compose run --rm --no-deps -T accounts
compose run --rm --no-deps -T fixture

# 설정값이 아닌 실제 pool max/min을 확인해 잘못 적용된 설정으로 비교하지 않게 한다.
snapshot | awk '/^hikaricp_connections_(max|min|idle|active)[{]/' > "$target/pool-config-check.txt"
if ! awk -v expected="$pool_size" '
  /^hikaricp_connections_max[{]/ { maxSeen++; maxOk=($NF+0 == expected) }
  /^hikaricp_connections_min[{]/ { minSeen++; minOk=($NF+0 == 10) }
  END { exit !(maxSeen == 1 && minSeen == 1 && maxOk && minOk) }
' "$target/pool-config-check.txt"; then
  echo '실제 pool max/min이 요청한 값과 다릅니다. pool-config-check.txt를 확인하세요.' >&2
  exit 1
fi

# 결과 폴더는 SQL 준비 서비스가 새로 만든다. 이미 있으면 준비 단계에서 중단한다.
{
  echo "startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ "$mode" == smoke ]]; then
    echo 'mode=smoke vus=2 iterationsPerVu=5 expectedListRequests=20'
  else
    echo "mode=load vus=$load_vus warmup=${warmup_seconds}s measurement=60s gracefulStop=25s p95Ms=${p95_ms:-unset}"
    echo 'model=constant-vus thinkSeconds=1 variants=all,reserved'
    echo "warmupStartMode=simultaneous measurementStartMode=$start_mode"
    if [[ "$start_mode" == staggered ]]; then echo 'startSpreadSeconds=5'; else echo 'startSpreadSeconds=0'; fi
    echo 'latencyWindows=initial0to10s,later10to60s,drain60sPlus classification=requestStart basis=scenarioStart'
    echo 'resourceSampling=enabled targetIntervalSeconds=2 sampledMaxIsNotAnAbsolutePeak=true'
    echo 'measurementLifecycle=stopSamplerBeforeRemovingOwnedK6'
    echo 'p95 criterion is an experimental target, not a production SLO'
  fi
  echo 'users=200 reservations=100000'
  echo "requestedPoolMax=$pool_size poolMinIdle=10 appRecreatedForPoolComparison=$pool_comparison"
  echo "requestedAppCpus=$app_cpus"
  cat "$target/pool-config-check.txt"
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
    MSYS_NO_PATHCONV=1 docker inspect --format '{{.Name}} {{.Image}} startedAt={{.State.StartedAt}} nanoCpus={{.HostConfig.NanoCpus}} memoryBytes={{.HostConfig.Memory}}' "$container_id"
  done
} > "$target/metadata.txt"

if [[ "$mode" == load ]]; then
  echo "warm-up: 서로 다른 회원 ${load_vus}명으로 ${warmup_seconds}초. 다음 측정과 별도 k6 프로세스입니다."
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
if MSYS_NO_PATHCONV=1 docker inspect "$measurement_container" >/dev/null 2>&1; then
  echo '동일 이름의 컨테이너가 이미 있습니다. 기존 컨테이너를 변경하지 않고 중단합니다.' >&2
  exit 1
fi
trap cleanup_measurement EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ "$mode" == load ]]; then
  bash "$script_dir/sample-resources.sh" "$target" "$compose_file" "$measurement_container" &
  sampler_pid=$!
fi
echo "$mode 측정 시작"
measurement_started=true
# 종료된 컨테이너를 수집기 회수 전까지 보존한다. --rm을 사용하면 마지막 stats와 삭제가 경합한다.
if compose run --no-deps -T --name "$measurement_container" --label "kan134.owner=$measurement_owner" k6; then
  exit_code=0
else
  exit_code=$?
fi
cleanup_measurement
echo "measurementFinishedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/metadata.txt"
if ! snapshot > "$target/metrics-after.txt"; then
  echo '메트릭 수집 실패. 앱 관리 포트를 확인하세요.' > "$target/metrics-after.txt"
fi
printf '%s\n' "$exit_code" > "$target/exit-code.txt"
echo "결과: $target / 종료 코드: $exit_code"
exit "$exit_code"
