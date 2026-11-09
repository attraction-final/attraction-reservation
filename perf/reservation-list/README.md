# KAN-134 내 예약 목록 HTTP smoke 테스트

실제 Spring Boot 앱과 Spring Security를 거쳐 응답 정확성을 확인한다.
현재는 **부하를 높이기 전 연결 검증** 단계다. 성능 목표 달성이나 운영 처리 한계를 판단하지 않는다.

확인한 실행 결과와 구성 오류 조치는 [KAN-134 실험 기록](../../docs/troubleshooting/reservation-list-load-test.md)에 정리한다.

## 실행 구성

Python·Node.js·npm·host의 MySQL client 설치가 필요 없다. k6는 JavaScript 테스트를 자체 runtime에서 실행한다.
Docker Desktop(Linux containers), Docker Compose v2, Java 17, Git Bash가 필요하다.
실행기 `run.sh`는 Git Bash의 bash·curl·awk·sha256sum 등의 기본 도구를 사용한다.
PowerShell에서 실행할 때도 이 스크립트는 Git Bash 터미널에서 실행한다.

| 파일/도구 | 역할 |
| --- | --- |
| `run.sh` | 전용 환경 기동 → 회원 준비 → SQL 준비 → k6 → 결과 수집을 순서대로 실행 |
| `prepare-accounts.js`, `auth.js` | k6에서 실제 가입·CSRF·로그인 처리. BCrypt를 SQL로 흉내 내지 않음 |
| `prepare.sh`, `prepare.sql` | MySQL 컨테이너의 client로 fixture 생성 또는 기존 fixture 검증 |
| `expected.sql` | API 응답과 비교할 회원별 예약 ID·정렬·DTO·건수를 DB에서 추출 |
| `reservation-list.js` | setup에서 로그인, VU에서 목록 조회, handleSummary에서 집계 결과 저장 |
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
| 실제 요청 회원 | 서로 다른 회원 2명 |
| smoke | 2 VUs × 5 iterations × 2조건 = 목록 조회 20건, iteration마다 1초 대기 |
| 기능 합격 | 미인증 401, 조회 오류 0, 회원별 ID·순서·DTO·필터·페이지·전체 건수 일치 |
| 성능 합격 | 아직 정하지 않음. 소규모·warm-up 없는 값으로 성능 결론을 내리지 않음 |
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
  → MySQL client: prepare.sql 실행, expected.sql로 기대 결과 4행 저장
  → 실행 환경·측정 전 메트릭 기록
  → smoke k6: setup에서 회원 2명 로그인, VU에서 목록 20회 검증
  → 측정 후 메트릭·종료 코드 기록
```

## 결과 확인

결과는 `perf/reservation-list/results/<RUN_ID>/`에 저장되며 Git에서 제외한다.

| 파일 | 확인할 내용 | 해석 범위 |
| --- | --- | --- |
| `summary.json` | check 실패, invalid rate, 조회 20건 완료, 조건별 duration | 인증·로그인 요청이 포함된 전체 HTTP 지표 대신 목록 전용 지표 사용 |
| `fixture.jsonl` | 회원 2명의 조건별 DB 기대 결과 4행 | 토큰·비밀번호 없음. API 응답에서 복사한 기대값이 아님 |
| `metadata.txt` | 시각, HEAD·수정 상태, jar/스크립트 hash, 실제 DB 건수·버전·인덱스·앱/DB/Redis image ID | 소스와 jar 일치 여부는 빌드 절차로 별도 관리 |
| `metrics-before.txt`, `metrics-after.txt` | Hikari·JVM·HTTP Prometheus 메트릭 | 전후 누적값/순간값. 구간 최대 CPU·메모리나 요청당 쿼리 수를 증명하지 않음 |
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

smoke 통과 후 p95 목표·허용 오류율·동시 사용자 수·지속 시간을 정하고 warm-up을 측정 구간과 분리한다.
이후 앱/DB/발생기 자원·GC·pool 대기를 구간별로 수집한다. 앱·DB·k6가 같은 PC의 Docker 자원을 공유하므로
관측한 처리량을 운영 환경의 한계로 일반화하지 않는다. 이번 변경에서는 인덱스·pool·기능 API를 변경하지 않는다.

## 종료

```bash
docker compose -f perf/reservation-list/compose.yaml down
```

볼륨과 DB 데이터는 유지된다. 다음에는 새 RUN_ID로 `run.sh`를 실행한다.

## 참고

- [k6 lifecycle: setup과 VU 실행 구분](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/)
- [k6 handleSummary 결과 저장](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/)
- 현재 ReservationController, SearchReservationListResponse, ReservationCustomRepositoryImpl, AuthController, CsrfController와 Flyway 스키마를 기준으로 작성했다.
