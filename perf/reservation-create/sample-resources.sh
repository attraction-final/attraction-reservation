#!/usr/bin/env bash
# 측정용 k6가 동작하는 동안 host에서 수집한다. Docker/HTTP 조회만 수행한다.
set -euo pipefail
if [[ $# -ne 3 || ! -d "$1" ]]; then
  echo '사용법: sample-resources.sh 결과폴더 compose파일 측정컨테이너이름' >&2
  exit 1
fi
target="$1"
compose_file="$2"
k6_name="$3"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
db_locks="${PERF_DB_LOCKS:-0}"
docker_cmd() { MSYS_NO_PATHCONV=1 docker "$@"; }
errors="$target/sampling-errors.txt"
: > "$errors"
: > "$target/resource-samples.jsonl"
: > "$target/metrics-samples.txt"
: > "$target/sampling-boundary-events.txt"
if [[ "$db_locks" == 1 ]]; then
  : > "$target/db-lock-samples.jsonl"
  : > "$target/db-lock-sampling-errors.txt"
  # 전용 로컬 MySQL의 관측용 연결이다. 앱 계정의 권한은 변경하지 않는다.
  if ! enabled="$(docker_cmd compose -f "$compose_file" exec -T -e MYSQL_PWD=perf-local-only mysql \
    mysql -uroot --connect-timeout=3 --batch --raw --skip-column-names \
    -e 'SELECT @@performance_schema;' 2>> "$target/db-lock-sampling-errors.txt")" || [[ "${enabled//$'\r'/}" != 1 ]]; then
    echo 'Performance Schema 확인 실패. 잠금 대기 0으로 해석하지 마세요.' >> "$errors"
    exit 1
  fi
fi
stats_stderr="$target/docker-stats-last-stderr.txt"

base_ids=()
ids_text="$(docker_cmd compose -f "$compose_file" ps -q app mysql redis)"
while IFS= read -r id; do
  id="${id//$'\r'/}"
  [[ -z "$id" ]] || base_ids+=("$id")
done <<< "$ids_text"
if [[ ${#base_ids[@]} -ne 3 ]]; then
  echo '앱·MySQL·Redis 컨테이너 3개를 찾지 못했습니다.' >> "$errors"
  exit 1
fi

sample=0
while [[ ! -e "$target/sampling-stop" ]]; do
  cycle_start=$SECONDS
  sample=$((sample + 1))
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  ids=("${base_ids[@]}")
  # 시작 직전/종료 직후에는 측정 컨테이너가 없을 수 있다. 이때 다른 컨테이너는 계속 수집한다.
  k6_running=false
  if [[ "$(docker_cmd inspect --format '{{.State.Running}}' "$k6_name" 2>/dev/null | tr -d '\r' || true)" == true ]]; then
    ids+=("$k6_name")
    k6_running=true
  fi
  printf '{"kind":"cycle","sample":%s,"startedAt":"%s","k6Running":%s}\n' \
    "$sample" "$started_at" "$k6_running" >> "$target/resource-samples.jsonl"

  # 한 주기의 scrape와 docker stats도 완전히 동시인 것은 아니다. 시각으로 관측 범위를 구분한다.
  if scrape="$(curl --fail --silent --show-error --max-time 5 \
    http://127.0.0.1:18107/actuator/prometheus 2>> "$errors")"; then
    {
      echo "# sample=$sample scrapeCompletedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf '%s\n' "$scrape" |
        awk '/^(hikaricp_|jvm_gc_|jvm_memory_used_bytes|process_cpu_usage|process_uptime_seconds|http_server_requests_seconds_|reservation_create_(slot_(lookup|postlookup)|attraction_lookup)_)/'
    } >> "$target/metrics-samples.txt"
  else
    echo "sample=$sample at=$started_at source=prometheus result=failed" >> "$errors"
  fi

  if [[ "$db_locks" == 1 ]]; then
    if lock_sample="$(docker_cmd compose -f "$compose_file" exec -T -e MYSQL_PWD=perf-local-only mysql \
      mysql -uroot --connect-timeout=3 --default-character-set=utf8mb4 --batch --raw --skip-column-names \
      < "$script_dir/lock-waits.sql" 2>> "$target/db-lock-sampling-errors.txt")" && [[ -n "$lock_sample" ]]; then
      printf '%s\n' "$lock_sample" >> "$target/db-lock-samples.jsonl"
    else
      echo "sample=$sample at=$started_at source=dbLockSampling result=failed" >> "$errors"
    fi
  fi

  format='{"kind":"dockerStats","sample":'"$sample"',"sampleStartedAt":"'"$started_at"'","stats":{{json .}}}'
  if stats_text="$(docker_cmd stats --no-stream --format "$format" "${ids[@]}" 2> "$stats_stderr")"; then
    [[ -z "$stats_text" ]] || printf '%s\n' "$stats_text" >> "$target/resource-samples.jsonl"
    # stopped 컨테이너는 stats가 0으로 끝나도 출력에 없을 수 있다. 누락을 성공 표본으로 세지 않는다.
    if [[ "$k6_running" == true && "$stats_text" != *\"Name\":\"$k6_name\"* ]]; then
      state="$(docker_cmd inspect --format '{{.State.Status}}' "$k6_name" 2>/dev/null | tr -d '\r' || true)"
      if [[ "$state" == exited || "$state" == dead ]]; then
        echo "sample=$sample at=$started_at event=K6_SAMPLE_MISSING_AT_BOUNDARY state=$state" >> "$target/sampling-boundary-events.txt"
      else
        echo "sample=$sample at=$started_at source=k6StatsMissing result=failed state=${state:-unknown}" >> "$errors"
      fi
    fi
  else
    state="$(docker_cmd inspect --format '{{.State.Status}}' "$k6_name" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$k6_running" == true && ( "$state" == exited || "$state" == dead ) ]]; then
      # 종료 경계의 원문은 별도로 보존한다. 다른 오류를 경계 오류로 간주하지 않는다.
      {
        echo "sample=$sample at=$started_at event=K6_STOPPED_DURING_STATS state=$state"
        cat "$stats_stderr"
      } >> "$target/sampling-boundary-events.txt"
      # batch 전체 실패로 서버 표본까지 잃지 않도록 실행 중인 앱·DB·Redis를 다시 조회한다.
      recovered_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      recovery_format='{"kind":"dockerStats","sample":'"$sample"',"sampleStartedAt":"'"$recovered_at"'","recoveredAfterK6Stop":true,"stats":{{json .}}}'
      if recovered="$(docker_cmd stats --no-stream --format "$recovery_format" "${base_ids[@]}" 2>> "$errors")"; then
        [[ -z "$recovered" ]] || printf '%s\n' "$recovered" >> "$target/resource-samples.jsonl"
      else
        echo "sample=$sample at=$recovered_at source=baseStatsRecovery result=failed" >> "$errors"
      fi
    else
      cat "$stats_stderr" >> "$errors"
      echo "sample=$sample at=$started_at source=dockerStats result=failed state=${state:-unknown}" >> "$errors"
    fi
  fi
  printf '{"kind":"cycleEnd","sample":%s,"finishedAt":"%s"}\n' \
    "$sample" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/resource-samples.jsonl"
  # 처리 시간을 포함한 목표 간격은 2초다. 더 오래 걸리면 실제 기록 시각을 기준으로 해석한다.
  remaining=$((2 - (SECONDS - cycle_start)))
  if [[ "$remaining" -gt 0 && ! -e "$target/sampling-stop" ]]; then sleep "$remaining"; fi
done
