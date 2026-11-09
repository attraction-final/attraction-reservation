# KAN-134 내 예약 목록 HTTP smoke 테스트

실제 Spring Boot 앱과 Spring Security를 거쳐 응답 정확성을 확인한다.
smoke 회귀와 warm-up을 분리한 첫 10 VUs 부하 측정을 완료했다. 기능 검증은 통과했으며 성능 합격 기준은 미설정이었다.
주기적 자원 수집과 20 VUs 측정까지 확인했다. load-02·03의 마지막 Docker 수집은 각 1회 누락됐다.
수집 종료 순서를 개선한 load-07도 50 VUs 기능 검증을 통과했고 수집·정리 오류는 없었다. 복구·중단 경로는 아직 미검증이다.
load-10에서 첫 10초의 지연 집중을 확인했다. 다음은 같은 최대 100 VUs에서 측정 시작만 5초에 분산하는 비교 실험이다.
운영 처리 한계를 판단한 결과는 아니다.

확인한 실행 결과와 구성 오류 조치는 [KAN-134 실험 기록](../../docs/troubleshooting/reservation-list-load-test.md)에 정리한다.

## 실행 구성

Python·Node.js·npm·host의 MySQL client 설치가 필요 없다. k6는 JavaScript 테스트를 자체 runtime에서 실행한다.
Docker Desktop(Linux containers), Docker Compose v2, Java 17, Git Bash가 필요하다.
실행기 `run.sh`는 Git Bash의 bash·curl·awk·sha256sum 등의 기본 도구를 사용한다.
PowerShell에서 실행할 때도 이 스크립트는 Git Bash 터미널에서 실행한다.

| 파일/도구 | 역할 |
| --- | --- |
| `run.sh` | 전용 환경 기동 → 회원 준비 → SQL 준비 → k6 → 결과 수집을 순서대로 실행 |
| `sample-resources.sh` | load 측정 중 Docker stats·앱 메트릭을 주기적으로 읽고 종료 시 회수 |
| `prepare-accounts.js`, `auth.js` | k6에서 실제 가입·CSRF·로그인 처리. BCrypt를 SQL로 흉내 내지 않음 |
| `prepare.sh`, `prepare.sql` | MySQL 컨테이너의 client로 fixture 생성 또는 기존 fixture 검증 |
| `expected.sql` | API 응답과 비교할 회원별 예약 ID·정렬·DTO·건수를 DB에서 추출 |
| `reservation-list.js` | smoke / warmup / load 프로필, setup에서 로그인, VU에서 조회, 집계 결과 저장 |
| `compose.yaml`, `application-perf.yaml` | 기존 앱/DB와 분리된 개인 테스트 환경 |

준비용 k6 실행과 실제 smoke 실행은 별도 프로세스다. 회원 200명 준비 작업은 목록 성능 지표에 섞이지 않는다.
기존 `perf/order-list` 구성은 변경하지 않는다. 이전 Node.js 방식의 `smoke-01` 결과와 기존 DB도 지우지 않는다.
기존 `.local` 파일은 새 구성에서 사용하지 않으며 새 토큰 파일도 만들지 않는다.

## 조건과 합격 기준

| 항목 | 설정 |
| --- | --- |
| 대상 | `GET /api/reservations/me?page=0&size=10`, 동일 요청의 `status=RESERVED` 조건 |
| 데이터 | 회원 200명 × 500건 = 100,000건, 어트랙션 10개, 슬롯 500개 |
| 상태 | 회원별 RESERVED·COMPLETED·CANCELLED·NO_SHOW 각 125건 |
| 실제 요청 회원 | smoke는 2명, warm-up/load는 TEST_VUS로 10·20·50·100명 선택 (기본 10명) |
| smoke | 2 VUs × 5 iterations × 2조건 = 목록 조회 20건, iteration마다 1초 대기 |
| 기능 합격 | 미인증 401, 조회 오류 0, 회원별 ID·순서·DTO·필터·페이지·전체 건수 일치 |
| 성능 합격 | load에서 p95_ms를 명시한 경우에만 적용. smoke는 성능 합격 기준 없음 |
| 앱 자원 | CPU 1개, 메모리 1GiB, JVM heap 256~512MiB, Hikari 최대 10개 |
| MySQL / Redis / 부하용 k6 | CPU 1 / 0.5 / 1개, 메모리 1GiB / 256MiB / 512MiB |
| 접근 | 앱 `127.0.0.1:18096`, 관리 포트 `127.0.0.1:18097`, DB/Redis는 내부 네트워크 |

조회용 합성 이력이며 실제 서비스의 사용자 이력 분포를 대표한다고 가정하지 않는다.
과거 날짜의 IN_USE 티켓을 사용하므로 생성·결제·상태 전이 업무 검증용으로 사용하지 않는다.
관련 쓰기 스케줄러는 비활성화한다. Flyway로 스키마를 만들고 Hibernate는 validate만 수행한다.
SQL은 Generated Column에 직접 값을 넣지 않는다.

## 실행 순서 — 프로젝트 루트의 Git Bash

### 1. 앱 jar 준비

```bash
./gradlew bootJar
```

이미 현재 소스로 빌드한 jar가 있고 코드가 바뀌지 않았다면 생략한다. 테스트 실행 명령이 아니다.
앱 코드를 수정했다면 다시 빌드하고 실행 중인 app도 재생성한다.
HEAD와 jar hash를 저장하지만 hash만으로 jar가 현재 소스와 일치함을 증명하지는 않는다.

### 2. 준비부터 smoke까지 실행

```bash
bash perf/reservation-list/run.sh smoke-02
```

기존 `smoke-01` 결과와 구분하려고 새 이름을 사용한다. 재실행은 `smoke-03`처럼 새 이름으로 한다.
결과 폴더가 이미 있으면 덮어쓰지 않고 중단한다. 앱이 준비되는 동안 준비용 k6가 기다린다.
처음이면 실제 회원가입 후 SQL로 100,000건을 만들며, 이미 있으면 테스트 계정과 fixture 구조·건수를 확인해 재사용한다.
기존 데이터를 삭제하거나 덮어쓰는 기능은 없다.

기존 테스트 계정은 가입 시 409가 나올 수 있다. 같은 테스트 비밀번호로 실제 로그인까지 확인한 경우에만 재사용한다.
준비 도중 실패하면 기존 결과와 DB 상태를 먼저 확인한다. 같은 이름으로 재실행하거나 DB를 임의로 초기화하지 않는다.
SQL의 `perf_guard` CHECK 위반은 기대한 테스트 데이터와 다르다는 뜻이다. 실패한 SQL 위치와 기존 데이터를 확인한다.
회원가입은 SQL 트랜잭션 밖의 API이므로 일부 계정이 먼저 남을 수 있지만, 다음 실행에서 정상 로그인되는 계정은 재사용한다.
기존 Node.js fixture 기본값과 다른 규모의 데이터는 이번 고정 fixture로 자동 변환하지 않는다.

내부 실행 순서는 다음과 같다.

```text
mysql·redis·app 기동
  → 준비용 k6: 앱 준비 확인, 테스트 계정 200명 생성/로그인 확인
  → MySQL client: prepare.sql 실행, expected.sql로 후보 100명의 기대 결과 200행 저장
  → 실행 환경·측정 전 메트릭 기록
  → smoke k6: setup에서 회원 2명 로그인, VU에서 목록 20회 검증
  → 측정 후 메트릭·종료 코드 기록
```

### 3. 첫 부하 측정 — 실험 기준을 정한 다음 실행

| 구간 | 조건 | 결과 |
| --- | --- | --- |
| warm-up | 같은 회원 10명, constant-vus, 20초, iteration마다 1초 대기 | warmup-summary.json |
| 측정 | 회원 10명, constant-vus, 60초, iteration마다 1초 대기 | summary.json |
| 완료 대기 | 각 구간 종료 시 진행 중 iteration에 최대 25초 허용 | 실제 종료 시각은 목표 duration보다 늦을 수 있음 |

```bash
bash perf/reservation-list/run.sh load-01 load 500
```

마지막 `500`은 전체 조회와 RESERVED 조회 **각각 p95 < 500ms**를 임시 실험 기준으로 적용한다.
공식 서비스 SLO가 아니며, 목표 수치는 실험 전에 선택한다. 기준을 적용하지 않고 우선 관측만 할 때는 마지막 인자를 생략한다.

```bash
bash perf/reservation-list/run.sh load-01 load
```

두 명령은 대안이다. 같은 RUN_ID로 둘 다 실행하지 않는다. 기준 없이 실행해 종료 코드가 0이면 기능 검증 통과를 의미하며 성능 목표 통과를 뜻하지 않는다.
어느 경우든 HTTP 오류율·응답 불일치율 0과 check 전체 통과는 요구한다.
고정 동시 사용자 수를 유지하는 닫힌 모델이며, 응답이 느려지면 요청률이 낮아진다. 고정 RPS를 발생시키는 테스트가 아니다.

warm-up과 측정은 별도 k6 프로세스로 실행해 지표를 합치지 않는다. 두 프로세스는 같은 계정을 사용하고 각각 setup에서 로그인한다.
기능 검증에 실패한 warm-up 뒤에는 측정을 시작하지 않는다. before 메트릭은 warm-up 완료 후 기록한다.
현재는 단계별 부하 증가가 아닌 첫 10 VUs 실험이며, 운영 수용량을 측정하는 프로필로 해석하지 않는다.

### 4. 주기적 자원 수집을 포함한 다음 실행

```bash
bash perf/reservation-list/run.sh load-02 load
```

부하 조건은 load-01과 같은 10 VUs·60초이며 성능 합격 기준은 적용하지 않는다.
warm-up 뒤에 수집 프로세스가 시작돼 측정용 k6와 병렬로 동작한다. warm-up의 자원은 수집하지 않는다.
목표 간격은 처리 시간을 포함한 2초지만 Docker stats·scrape 시간에 따라 길어질 수 있어 실제 시각을 기록한다.
HTTP 검증 통과와 수집 성공을 별개로 확인한다. 실행기 종료 시 수집 중단을 요청하고 프로세스를 회수한다.

### 5. 다음 단계: 20 VUs

```bash
TEST_VUS=20 bash perf/reservation-list/run.sh load-03 load
```

warm-up 20초·측정 60초·iteration마다 1초 대기·컨테이너 자원 제한·수집 방식은 유지하고 회원 20명으로 늘린다.
성능 합격 기준은 적용하지 않는다. 변경한 기대 결과 SQL과 새 VU 설정의 실행 검증도 함께 수행한다.
현재 TEST_VUS는 10·20·50·100을 지원한다. 생략하면 10이며 smoke는 계속 2명을 사용한다.
기대 결과는 항상 후보 회원 100명·조건별 2행씩 200행을 추출하고 k6가 필요한 회원 수만 선택한다.
기존 결과 폴더의 4행/20행 fixture는 과거 실행 자료로 보존하며 새 실행에서 덮어쓰지 않는다.

### 6. 초기 CPU 표본의 구간 확인

load-03에서 앱 CPU 91.99%는 첫 번째 표본이었다. 자원 수집은 setup 로그인도 포함하므로 목록 조회의 CPU 포화로 단정하지 않는다.
다음 명령으로 같은 20 VUs 조건을 다시 실행한다.

```bash
TEST_VUS=20 bash perf/reservation-list/run.sh load-04 load
```

새 summary의 `reservation_list_setup_started_at_ms`, `reservation_list_setup_finished_at_ms`는 setup 경계,
`reservation_list_vu_started_at_ms`의 min/max는 VU별 첫 iteration 시작 범위다. 모두 UTC epoch milliseconds다.
이 시각을 자원 주기의 시작/종료 시각과 대조한다. 서로 다른 도구의 관측 구간이라 시각이 겹친다는 이유만으로 인과관계를 확정하지 않는다.
기존 load-03에는 이 marker가 없어 정확한 로그인 종료/VU 시작 시각을 소급해 확인할 수 없다.

### 7. 다음 단계: 50 VUs

```bash
TEST_VUS=50 bash perf/reservation-list/run.sh load-05 load
```

load-04는 marker와 기능 검증을 확인했고 이전 CPU 91.99% 표본은 재현되지 않았다. 원인을 로그인으로 확정한 것은 아니다.
다음은 50 VUs로 늘리고 warm-up 20초·측정 60초·iteration당 1초 대기·자원 제한·수집 방식을 유지한다.
같은 100,000건에서 더 많은 회원의 이력을 요청하므로 작업 집합도 커진다. p95 기준은 이번에도 미설정이다.
새 후보 fixture 100행과 50 VUs 설정은 load-05에서 검증했다.

### 8. 같은 50 VUs 반복

```bash
TEST_VUS=50 bash perf/reservation-list/run.sh load-06 load
```

스크립트·데이터·시간·대기·자원 제한을 추가 변경하지 않고 p99 증가와 자원 사용의 반복 여부를 관측한다.
load-05의 앱 CPU 97.36%는 VU 시작 전의 첫 표본이었다. 순수 목록 처리의 CPU 최대로 해석하지 않는다.
단일 반복만으로 원인이나 SLO 충족을 확정하지 않고 앞선 실행과 함께 문서에 기록한다.

### 9. 수집 종료 순서 개선 검증

```bash
TEST_VUS=50 bash perf/reservation-list/run.sh load-07 load
```

load-06은 목록 5,960건과 check 29,801회가 통과했지만 마지막 stats에서 이미 삭제된 k6를 조회하는 오류가 발생했다.
측정용 k6에는 `--rm`을 사용하지 않고, 종료 후 수집기를 회수한 다음 이번 실행 owner label·Compose 소속을 확인해 정리한다.
동일 이름의 기존 컨테이너가 있으면 중단한다. 사용자 중단 시에도 이번 실행 소유의 k6와 수집기만 정리한다.
앱·DB·Redis와 기존 데이터는 정리 대상이 아니다.

프로세스가 멈추는 순간 stats 자체가 끝나는 경우도 있어, stopped 상태가 확인된 경우의 경계 오류는 별도 기록한다.
batch stats 실패 시 앱·DB·Redis를 다시 수집해 전체 서버 표본 누락을 줄이고 복구 표본에는 별도 표시와 시각을 남긴다.
그 외 오류는 일반 수집 오류로 유지한다. 부하·시간·자원 제한은 이전과 동일하다.
load-07에서 측정·수집·정리 코드 모두 0, 오류·경계 이벤트 파일 비어 있음, Docker 표본 124개·앱 scrape 31회를 확인했다.
복구 표본이 없어 복구 분기와 중단 시 cleanup까지 검증한 것은 아니다. 다시 실행할 때는 새로운 RUN_ID를 사용한다.

### 10. 다음 단계: 100 VUs

```bash
TEST_VUS=100 bash perf/reservation-list/run.sh load-08 load
```

회원 100명으로 늘리고 warm-up 20초·측정 60초·iteration당 1초 대기·자원 제한·수집·정리 방식을 유지한다.
기존 데이터 100,000건은 바꾸지 않으며 SQL 기대 결과는 후보 회원 100명·조건별 2행씩 총 200행을 만든다.
성능 합격 기준은 계속 미설정이다. setup 로그인 비용은 목록 전용 Trend에 포함되지 않으며 marker로 구분한다.
k6의 CPU·메모리도 함께 확인해 부하 발생기 한계와 앱의 한계를 구분한다. 새 SQL 범위·100 VUs의 실제 실행은 load-08에서 확인했다.

### 11. 같은 100 VUs 반복

```bash
TEST_VUS=100 bash perf/reservation-list/run.sh load-09 load
```

코드·데이터·pool·측정 시간을 추가 변경하지 않고 시작 직후의 커넥션 대기와 p99 증가가 반복되는지 확인한다.
load-08은 오류 없이 종료했지만 pending > 0은 30개 표본 중 1개였으므로 지속적인 pool 부족이라고 확정하지 않는다.
관측된 대기는 connection 획득 대기다. row lock 대기나 지연의 직접 원인으로 혼동하지 않는다.

### 12. 100 VUs를 유지하고 시간 구간별 지연 확인

```bash
TEST_VUS=100 bash perf/reservation-list/run.sh load-10 load
```

부하·계정·데이터·pool·측정 시간은 유지하고 load의 전체 지표에 다음 구간별 지표를 추가했다.
기준은 setup 종료 뒤 실행되는 k6 scenario의 공통 startTime이다. scenario 시작 시각도 Gauge로 남긴다.

| phase | 요청 시작 시각 기준 범위 | 의미 |
| --- | --- | --- |
| initial | 0초 이상~10초 미만 | 시작 직후 요청 |
| later | 10초 이상~60초 미만 | 이후 요청. 완전히 안정된 구간이라고 가정하지 않음 |
| drain | 60초 이상 | 완료 대기 중 진행 중 iteration이 시작한 추가 요청 |

각 구간의 all/reserved별 duration·requests·invalid 지표를 `reservation_list_<phase>_<variant>_*`로 저장한다.
기존 전체 지표도 유지한다. initial/later의 두 조회 조건은 각각 표본이 있어야 하며 응답 검증 실패율은 0이어야 한다.
drain 요청은 발생을 강제하지 않는다. 구간별 요청 수의 합과 전체 요청 수도 결과 확인 시 비교한다.
예를 들어 9.9초에 시작해 10.2초에 끝난 요청은 initial이다. 응답 완료 시각 기준으로 옮기지 않는다.
구간별 p99를 단순 평균해 전체 p99로 계산하지 않는다. 구간별 요청률이 필요하면 k6 Counter rate의 전체 실행 분모와 구분한다.
이 변경은 집계만 분리한 실험이며 초기 요청을 분산하거나 제거한 부하 변경이 아니다. 구간별 지표는 load-10에서 검증했다.

load-10에서 구간별 집계와 전체 요청 수 일치를 확인했다. initial은 조건별 900건, later는 조건별 4,939건이었고 drain은 0건이었다.
전체/RESERVED의 initial p99는 366.65/403.08ms, later p99는 39.35/14.12ms였다. 원인을 확정한 결과는 아니다.

### 13. 100 VUs 첫 요청을 5초에 분산하는 비교

```bash
START_MODE=staggered TEST_VUS=100 bash perf/reservation-list/run.sh load-11 load
```

START_MODE 기본값은 simultaneous다. staggered에서는 측정용 load의 첫 요청만 VU ID 순으로 scenario 시작 0~5초에 배치한다.
그 이후 전체·RESERVED 조회와 1초 대기는 그대로 반복한다. warm-up은 START_MODE와 관계없이 기존 동시 시작을 유지한다.
100 VUs·60초는 유지하지만 처음 5초의 실제 요청 활동은 점진적으로 늘어나므로 총 요청 수·요청률이 달라질 수 있다.
이는 시작 방식의 비교 실험이며 동일한 요청 도착 패턴에서 앱을 개선한 실험이 아니다.

`reservation_list_vu_started_at_ms`는 기존대로 첫 iteration 진입을 기록한다.
새 `reservation_list_vu_first_request_at_ms`의 min/max로 실제 첫 요청이 약 5초에 퍼졌는지 확인한다.
지연이나 CPU 상황에 따라 목표 시각에 정확히 실행된다는 보장은 없으므로 기록된 시각을 사용한다.
scenario 기준 initial/later/drain 집계는 유지한다. 새 분산 시작 프로필의 실제 실행은 아직 검증 전이다.

## 결과 확인

결과는 `perf/reservation-list/results/<RUN_ID>/`에 저장되며 Git에서 제외한다.

| 파일 | 확인할 내용 | 해석 범위 |
| --- | --- | --- |
| `summary.json` | 전체 check, 전체/구간별 invalid·요청 수·duration, setup/VU/scenario/첫 요청 시각 Gauge | 분산 시작에서는 iteration 진입과 실제 첫 요청 시각을 구분. check 자체는 기존 전체 검증을 유지 |
| `warmup-summary.json`, `warmup-exit-code.txt` | load의 별도 warm-up 결과와 종료 코드 | 측정 결과와 합산하지 않음. smoke에는 생성하지 않음 |
| `fixture.jsonl` | 후보 회원 100명의 조건별 DB 기대 결과 200행 | smoke는 2명, load는 선택한 10명/20명/50명/100명 사용. 토큰·비밀번호 없음 |
| `metadata.txt` | 시각, HEAD·수정 상태, jar/스크립트 hash, 실제 DB 건수·버전·인덱스·앱/DB/Redis image ID | 소스와 jar 일치 여부는 빌드 절차로 별도 관리 |
| `metrics-before.txt`, `metrics-after.txt` | Hikari·JVM·HTTP Prometheus 메트릭 | 전후 누적값/순간값. 구간 최대 CPU·메모리나 요청당 쿼리 수를 증명하지 않음 |
| `resource-samples.jsonl` | load의 앱·MySQL·Redis·측정용 k6 Docker stats와 주기 시작/종료 시각 | 표본 최대값은 절대적인 순간 최대값이 아님. 시작/종료 경계에는 k6 표본이 없을 수 있음 |
| `metrics-samples.txt` | load의 주기별 Hikari·JVM·HTTP 지표, scrape 완료 시각 | 여러 scrape를 이어 둔 분석용 텍스트이며 단일 Prometheus scrape 입력으로 쓰지 않음 |
| `sampling-errors.txt`, `sampling-exit-code.txt` | 수집 오류·프로세스 종료 코드 | 종료 코드 0만으로 누락 없는 수집을 보장하지 않음. 오류 파일과 주기·컨테이너별 표본 확인 |
| `sampling-boundary-events.txt` | k6 종료 상태가 확인된 경계 누락·오류와 원문 | k6 표본 누락을 정상 표본으로 세지 않음. 일반 오류와 별도로 확인 |
| `docker-stats-last-stderr.txt` | 마지막 batch stats의 stderr 진단용 파일 | 경계 이벤트/일반 오류 파일과 함께 해석 |
| `cleanup-exit-code.txt`, `cleanup-errors.txt` | 이번 실행 소유의 k6 정리 결과 | 정상 정리 코드는 0. API 종료 코드와 별개이며 실패 시 임의로 다른 컨테이너를 제거하지 않음 |
| `exit-code.txt` | k6 종료 코드, 정상 통과는 0 | 준비 단계 실패나 강제 종료 시 파일 자체가 없을 수 있음 |

목록 측정 지표는 `reservation_list_duration`, `reservation_list_all_duration`,
`reservation_list_reserved_duration`이다. `setup()`의 HTTP 요청은 k6 기본 HTTP 지표에 포함되지만
이 세 지표에는 포함되지 않는다. 토큰은 setup 데이터로만 전달하고 결과·콘솔·파일에 쓰지 않는다.
부하 k6가 시작하기 전 실패하면 `summary.json`이 없을 수 있다. 오류는 터미널과 필요 시 Compose 앱 로그에서 확인한다.
문서에는 핵심 조건·지표·판단·한계만 Markdown 표로 남기고 전체 로그나 results를 커밋하지 않는다.

### 이전 smoke 결과와 비교할 때

이전 Node.js 방식의 `smoke-01`은 실제 통과를 확인했다. 새 방식은 로그인 위치와 준비 절차가 바뀌었으므로 별도 실행으로 검증해야 한다.
기존 결과를 새 방식의 통과 근거로 간주하지 않는다. 환경·데이터와 warm-up 유무를 함께 기록한다.

## 다음 단계

첫 측정 전에 임시 p95 기준을 선택한다. 첫 10 VUs 결과를 확인한 뒤 부하 단계를 늘릴지 판단한다.
시간 구간별로 초기 지연 집중을 확인했고 다음은 분산 시작과 비교한다. 요청별 SQL 수·DB 실행 계획 수집은 아직 포함하지 않는다.
Docker stats와 앱 scrape는 동시에 취득한 값이 아니며 기록된 관측 시각을 함께 확인한다.
Docker 메모리와 JVM heap 사용량은 서로 다른 지표다. 수집기 자체의 CPU·I/O 비용도 있으므로 load-01과의 시간 차이를 최적화 효과로 주장하지 않는다.
앱·DB·k6가 같은 PC의 Docker 자원을 공유하므로
관측한 처리량을 운영 환경의 한계로 일반화하지 않는다. 이번 변경에서는 인덱스·pool·기능 API를 변경하지 않는다.

## 종료

```bash
docker compose -f perf/reservation-list/compose.yaml down
```

볼륨과 DB 데이터는 유지된다. 다음에는 새 RUN_ID로 `run.sh`를 실행한다.

## 참고

- [k6 lifecycle: setup과 VU 실행 구분](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/)
- [k6 handleSummary 결과 저장](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)
- [Docker stats의 형식과 메모리 해석](https://docs.docker.com/reference/cli/docker/container/stats/)
- [Compose run의 컨테이너 자동 삭제와 label](https://docs.docker.com/reference/cli/docker/compose/run/)
- [k6 execution의 scenario startTime](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/)
- 현재 ReservationController, SearchReservationListResponse, ReservationCustomRepositoryImpl, AuthController, CsrfController와 Flyway 스키마를 기준으로 작성했다.
