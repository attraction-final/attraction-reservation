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
docker_cmd() { MSYS_NO_PATHCONV=1 docker "$@"; }
errors="$target/sampling-errors.txt"
: > "$errors"
: > "$target/resource-samples.jsonl"
: > "$target/metrics-samples.txt"

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
    http://127.0.0.1:18097/actuator/prometheus 2>> "$errors")"; then
    {
      echo "# sample=$sample scrapeCompletedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf '%s\n' "$scrape" |
        awk '/^(hikaricp_|jvm_gc_|jvm_memory_used_bytes|process_cpu_usage|process_uptime_seconds|http_server_requests_seconds_)/'
    } >> "$target/metrics-samples.txt"
  else
    echo "sample=$sample at=$started_at source=prometheus result=failed" >> "$errors"
  fi

  format='{"kind":"dockerStats","sample":'"$sample"',"sampleStartedAt":"'"$started_at"'","stats":{{json .}}}'
  if ! docker_cmd stats --no-stream --format "$format" "${ids[@]}" \
    >> "$target/resource-samples.jsonl" 2>> "$errors"; then
    echo "sample=$sample at=$started_at source=dockerStats result=failed" >> "$errors"
  fi
  printf '{"kind":"cycleEnd","sample":%s,"finishedAt":"%s"}\n' \
    "$sample" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$target/resource-samples.jsonl"
  # 처리 시간을 포함한 목표 간격은 2초다. 더 오래 걸리면 실제 기록 시각을 기준으로 해석한다.
  remaining=$((2 - (SECONDS - cycle_start)))
  if [[ "$remaining" -gt 0 && ! -e "$target/sampling-stop" ]]; then sleep "$remaining"; fi
done
