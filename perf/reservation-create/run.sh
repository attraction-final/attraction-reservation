#!/usr/bin/env bash
# Git Bash용. 실행은 사용자가 하며 KAN-150 전용 환경만 준비한다.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/../.." && pwd)"
run_id="${1:-}"
scenario="${2:-sold-out}"
users="${TEST_VUS:-50}"
k6_cpus="${PERF_K6_CPUS:-1}"
db_locks="${PERF_DB_LOCKS:-0}"
pool_size="${PERF_DB_POOL_SIZE:-10}"
fixed_time="${PERF_FIXED_TIME:-}"
business_time_sql=NULL
time_mode=real
if [[ -n "$fixed_time" ]]; then
  # SQL과 JVM이 동일한 날짜를 사용하도록 KST 14시 형식만 허용한다.
  if [[ ! "$fixed_time" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T14:00:00\+09:00$ ]]; then
    echo 'PERF_FIXED_TIME은 YYYY-MM-DDT14:00:00+09:00 형식이어야 합니다.' >&2
    exit 1
  fi
  time_mode=fixed
  business_time_sql="'${fixed_time:0:10} 14:00:00'"
fi
if [[ "$pool_size" != 10 && "$pool_size" != 20 ]]; then
  echo 'PERF_DB_POOL_SIZE는 10 또는 20만 지원합니다.' >&2
  exit 1
fi
if [[ "$db_locks" != 0 && "$db_locks" != 1 ]]; then
  echo 'PERF_DB_LOCKS는 0 또는 1만 지원합니다.' >&2
  exit 1
fi
if [[ "$k6_cpus" != 1 && "$k6_cpus" != 2 ]]; then
  echo 'PERF_K6_CPUS는 1 또는 2만 지원합니다.' >&2
  exit 1
fi
if [[ $# -lt 1 || $# -gt 2 || ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,11}$ ]]; then
  echo '사용법: bash perf/reservation-create/run.sh RUN_ID [sold-out|single|spread] (RUN_ID 최대 12자)' >&2
  exit 1
fi
if [[ "$scenario" != sold-out && "$scenario" != single && "$scenario" != spread ]]; then
  echo '시나리오는 sold-out, single, spread만 허용합니다.' >&2
  exit 1
fi
if [[ "$users" != 50 && "$users" != 100 && "$users" != 200 && "$users" != 500 && "$users" != 1000 && "$users" != 2000 ]]; then
  echo 'TEST_VUS는 현재 단계에서 50, 100, 200, 500, 1000, 2000만 지원합니다.' >&2
  exit 1
fi
groups=1
capacity=20
expected_success=20
expected_rejection=$((users - expected_success))
if [[ "$scenario" != sold-out ]]; then capacity="$users"; expected_success="$users"; expected_rejection=0; fi
if [[ "$scenario" == spread ]]; then groups=5; capacity=$((users / groups)); fi
target="$script_dir/results/$run_id"
if [[ -e "$target" ]]; then echo '기존 결과는 덮어쓰지 않습니다. 새 RUN_ID를 사용하세요.' >&2; exit 1; fi
jar="$project_dir/build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar"
if [[ ! -f "$jar" ]]; then echo '먼저 현재 코드로 ./gradlew bootJar를 실행하세요.' >&2; exit 1; fi
export RUN_ID="$run_id" SCENARIO="$scenario" TEST_VUS="$users" PERF_UID="$(id -u)" PERF_GID="$(id -g)"
export PERF_K6_CPUS="$k6_cpus"
export PERF_DB_LOCKS="$db_locks"
export PERF_DB_POOL_SIZE="$pool_size"
export PERF_FIXED_TIME="$fixed_time"
compose_file="$script_dir/compose.yaml"
case "$(uname -s)" in MINGW*|MSYS*) compose_file="$(cygpath -w "$compose_file")" ;; esac
compose_args=(-f "$compose_file")
if [[ "$time_mode" == fixed ]]; then
  fixed_compose="$script_dir/compose.fixed-time.yaml"
  case "$(uname -s)" in MINGW*|MSYS*) fixed_compose="$(cygpath -w "$fixed_compose")" ;; esac
  compose_args+=(-f "$fixed_compose")
fi
docker_cmd() { MSYS_NO_PATHCONV=1 docker "$@"; }
compose() { docker_cmd compose "${compose_args[@]}" "$@"; }
mysql() { compose exec -T -e MYSQL_PWD=perf-local-only mysql mysql -uperf --default-character-set=utf8mb4 --batch --raw --skip-column-names reservation_create_perf "$@"; }
sql_file() { { printf "SET @run_id='%s'; SET @scenario='%s'; SET @users=%s; SET @business_kst=%s;\n" "$run_id" "$scenario" "$users" "$business_time_sql"; cat "$1"; } | mysql; }
snapshot() {
  curl --fail --silent --show-error --max-time 10 http://127.0.0.1:18107/actuator/prometheus |
    awk '/^(hikaricp_|jvm_gc_|jvm_memory_used_bytes|process_cpu_usage|process_uptime_seconds|http_server_requests_seconds_|reservation_create_(slot_(lookup|postlookup)|attraction_lookup)_)/'
}

compose up -d --wait --wait-timeout 120 mysql redis
# 실제 모드는 기존 시간 제한을 유지하고 고정 모드는 설정 날짜·시각을 검증한다.
if [[ "$time_mode" == fixed ]]; then
  # 월별 일수 등 날짜 오류도 DB에서 확인한다. 실패하면 앱 실행과 데이터 준비 전에 종료한다.
  checked_time="$(mysql -e "SELECT DATE_FORMAT(CAST($business_time_sql AS DATETIME),'%Y-%m-%dT%H:%i:%s');" | tr -d '\r')"
  if [[ "$checked_time" != "${fixed_time:0:19}" ]]; then
    echo 'PERF_FIXED_TIME 날짜 검증 실패' >&2
    exit 1
  fi
  kst_hm=1400
else
  kst_hm="$(mysql -e "SELECT DATE_FORMAT(UTC_TIMESTAMP()+INTERVAL 9 HOUR,'%H%i');" | tr -d '\r')"
fi
if [[ ! "$kst_hm" =~ ^[0-9]{4}$ ]] || (( 10#$kst_hm < 1000 || 10#$kst_hm >= 2110 )); then
  echo '실제 KST 10:00 이상~21:10 미만에 실행하세요. 오늘 21:50 슬롯의 마감 여유가 필요합니다.' >&2
  exit 1
fi
mkdir -p "$script_dir/results"
mkdir "$target"
compose up -d --force-recreate --no-deps app
compose run --rm --no-deps -T accounts
preparation_exit=0
sql_file "$script_dir/prepare.sql" > "$target/fixture.jsonl" 2> "$target/preparation-errors.txt" || preparation_exit=$?
printf '%s\n' "$preparation_exit" > "$target/preparation-exit-code.txt"
if [[ "$preparation_exit" -ne 0 ]]; then
  cat "$target/preparation-errors.txt" >&2
  echo 'SQL 준비 실패. preparation-errors.txt를 확인하세요.' >&2
  exit "$preparation_exit"
fi
if [[ "$(wc -l < "$target/fixture.jsonl")" -ne "$users" ]]; then echo "기대 결과 ${users}행 검증 실패" >&2; exit 1; fi
{
  echo "runId=$run_id startedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "businessTimeMode=$time_mode fixedBusinessTime=${fixed_time:-unset} realTimeClockUsedForJwtAndMetrics=true"
  echo "scenario=$scenario vus=$users iterationsPerVu=1 slots=$groups attractions=$groups capacityPerSlot=$capacity expectedSuccess=$expected_success expectedRejection=$expected_rejection"
  echo "appCpus=2 appMemory=1GiB poolMax=$pool_size poolMin=$pool_size generatorCpus=$k6_cpus generatorMemory=2GiB"
  echo 'warmup=none successAndRejectionLatencySeparated=true performanceSlo=unset'
  echo 'reservationStageMetricCollection=enabled lookupIncludesQueryAndWait=true postlookupEndsAt=afterCompletion'
  echo "dbLockSampling=$db_locks dbLockSamplingMode=currentWaitEdges"
  echo "setupData=tokenArray fixtureData=sharedArray launchDiagnostics=enabled requestTargetOffsetMs=$(( users >= 2000 ? 5000 : 1500 ))"
  echo "setupTimeoutSeconds=$(( users >= 2000 ? 240 : users >= 500 ? 120 : 60 )) minimumTokenValiditySeconds=$(( (users >= 2000 ? 240 : users >= 500 ? 120 : 60) + 120 ))"
  (cd -- "$project_dir" && git rev-parse HEAD && git status --short)
  sha256sum "$jar" "$script_dir/compose.yaml" "$script_dir/reservation-create.js" \
    "$script_dir/prepare.sql" "$script_dir/verify.sql" "$script_dir/run.sh" "$script_dir/compose.fixed-time.yaml" \
    "$script_dir/prepare-accounts.js" "$script_dir/sample-resources.sh" "$script_dir/lock-waits.sql" \
    "$script_dir/../reservation-list/auth.js" "$script_dir/../reservation-list/application-perf.yaml"
  mysql -e "SELECT VERSION(),UTC_TIMESTAMP(),UTC_TIMESTAMP()+INTERVAL 9 HOUR;"
  for service in app mysql redis; do
    container_id="$(compose ps -q "$service" | tr -d '\r')"
    docker_cmd inspect --format '{{.Name}} {{.Image}} startedAt={{.State.StartedAt}} nanoCpus={{.HostConfig.NanoCpus}} memoryBytes={{.HostConfig.Memory}}' "$container_id"
  done
} > "$target/metadata.txt"
snapshot > "$target/metrics-before.txt"
awk '/^hikaricp_connections_(max|min)[{]/' "$target/metrics-before.txt" > "$target/pool-config-check.txt"
if ! awk -v expected="$pool_size" '
  /^hikaricp_connections_max[{]/ { maxSeen++; maxOk=($NF+0 == expected) }
  /^hikaricp_connections_min[{]/ { minSeen++; minOk=($NF+0 == expected) }
  END { exit !(maxSeen == 1 && minSeen == 1 && maxOk && minOk) }
' "$target/pool-config-check.txt"; then
  echo "실제 pool max/min $pool_size/$pool_size 검증 실패" >&2
  exit 1
fi
container_name="reservation-create-$run_id"
owner="$run_id-$$"
if docker_cmd inspect "$container_name" >/dev/null 2>&1; then echo '동일 이름의 컨테이너가 있습니다.' >&2; exit 1; fi
sampler_pid=''
started=false
cleaned=false
cleanup() {
  if [[ "$cleaned" == true ]]; then return; fi
  cleaned=true
  local sampling_exit=0 cleanup_exit=0 identity=''
  if [[ -n "$sampler_pid" ]]; then
    : > "$target/sampling-stop"
    wait "$sampler_pid" || sampling_exit=$?
    printf '%s\n' "$sampling_exit" > "$target/sampling-exit-code.txt"
  fi
  if [[ "$started" == true ]]; then
    identity="$(docker_cmd inspect --format '{{index .Config.Labels "kan150.owner"}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}' "$container_name" 2>> "$target/cleanup-errors.txt" | tr -d '\r')" || cleanup_exit=1
    if [[ "$identity" == "$owner|attraction-reservation-create-perf|k6" ]]; then
      docker_cmd inspect --format '{"status":{{json .State.Status}},"running":{{.State.Running}},"oomKilled":{{.State.OOMKilled}},"exitCode":{{.State.ExitCode}},"memoryBytes":{{.HostConfig.Memory}},"nanoCpus":{{.HostConfig.NanoCpus}}}' \
        "$container_name" > "$target/measurement-state-before-cleanup.json" 2>> "$target/cleanup-errors.txt" || cleanup_exit=1
      if [[ "$(docker_cmd inspect --format '{{.State.Running}}' "$container_name" | tr -d '\r')" == true ]]; then
        docker_cmd stop --timeout 5 "$container_name" >/dev/null 2>> "$target/cleanup-errors.txt" || cleanup_exit=1
      fi
      docker_cmd rm "$container_name" >/dev/null 2>> "$target/cleanup-errors.txt" || cleanup_exit=1
    else
      echo '소유 확인 실패. 컨테이너를 제거하지 않습니다.' >> "$target/cleanup-errors.txt"
      cleanup_exit=1
    fi
  fi
  printf '%s\n' "$cleanup_exit" > "$target/cleanup-exit-code.txt"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
bash "$script_dir/sample-resources.sh" "$target" "$compose_file" "$container_name" &
sampler_pid=$!
echo "measurementStartedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/metadata.txt"
started=true
measurement_exit=0
compose run --no-deps -T --name "$container_name" --label "kan150.owner=$owner" k6 || measurement_exit=$?
printf '%s\n' "$measurement_exit" > "$target/exit-code.txt"
cleanup
trap - EXIT INT TERM
echo "measurementFinishedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/metadata.txt"
snapshot > "$target/metrics-after.txt"
# k6가 실패해도 DB 상태를 읽어 실패 원인과 정합성을 별도로 남긴다.
verification_exit=0
sql_file "$script_dir/verify.sql" > "$target/db-verification.txt" 2> "$target/db-verification-errors.txt" || verification_exit=$?
printf '%s\n' "$verification_exit" > "$target/db-verification-exit-code.txt"
echo "결과: $target (HTTP=$measurement_exit, DB=$verification_exit). 수집·정리 상태도 함께 확인하세요."
if [[ "$measurement_exit" -ne 0 ]]; then exit "$measurement_exit"; fi
exit "$verification_exit"
