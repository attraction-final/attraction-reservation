# 로그인 부하 테스트 및 성능 진단

## 목적과 최종 판단

개인 테스트 환경에서 이메일·비밀번호 로그인 정확성과 반복 부하 성능을 확인하고,
JFR로 주요 실행 경로를 조사했다.

- 정상 로그인과 인증 거절 동작을 확인했다.
- 가상 사용자 10명으로 60초간 로그인 반복 부하를 3회 측정했다.
- 로그인 1,630건이 모두 성공했고 응답 검사 11,440개가 통과했다.
- 별도 JFR 진단에서 BCrypt가 포함된 실행 표본이 약 94.13%였다.
- 이 결과만으로 CPU 포화나 전체 응답 시간의 구성 비율을 확정하지 않았다.
- 비밀번호 검증 강도를 낮추거나 서비스 코드를 변경하지 않았다.

이번 결과는 기준 성능과 진단 기록이다.
성능 개선 전후 비교나 배포 환경의 최대 처리량 검증 결과는 아니다.

## 실험 환경

| 항목 | 설정 |
|---|---|
| 실행 환경 | 개인 Mac에서 앱·DB·부하 발생기 실행 |
| Java | Corretto OpenJDK 17.0.19 |
| 앱 힙 | 최소 256MB / 최대 512MB |
| 앱 주소 | 127.0.0.1:18090 |
| 관리 포트 | 127.0.0.1:18091 |
| MySQL | 기존 개인 실험용 Compose의 13316 포트 |
| 전용 DB | attraction_auth_perf |
| 회원 | ACTIVE / USER / LOCAL 회원 10명 |
| 회원 조회 인덱스 | uk_users_email(email), 고유 인덱스 |
| Hikari 최대 연결 수 | 10 |
| Redis | 기존 개인 실험용 Compose의 16396 포트 |
| Redis 논리 DB | 1 |
| 기준 부하 | 10 VU / 60초 / 로그인 후 1초 대기 |
| 비밀번호 검증 | 기존 BCrypt 설정 유지 |

Redis 논리 DB 1은 다른 실험과 갱신 토큰 키가 충돌하지 않도록 분리했다.
Redis 인스턴스의 CPU와 메모리는 공유한다.

애플리케이션·DB·부하 발생기도 같은 Mac의 자원을 공유한다.

주문 자동 취소 및 결제 관련 스케줄러는 실험 설정에서 비활성화했다.
입장권 만료 스케줄러의 실행 간격은 24시간으로 설정했으며,
입장권 데이터가 없는 전용 DB를 사용했다.

설정 파일의 인증 정보는 개인 실험 전용 공개 값이다.
실제 서비스의 비밀값을 넣지 않는다.

## 파일 구성

| 파일 | 역할 |
|---|---|
| application-perf.yaml | 전용 DB·Redis·포트·스케줄러 설정 |
| start-app.py | Java 17 확인 및 개인 앱 실행 |
| prepare-users.py | 전용 회원 10명 준비 및 검증 기준 저장 |
| check-login.py | 실제 API의 로그인·인증 거절·쿠키·Redis 검사 |
| auth-login.js | k6 간단 검사 및 60초 반복 부하 |
| .gitignore | 인증 준비 파일과 원본 결과의 Git 제외 |

`.local/`과 `results/`는 Git에서 제외한다.

## 로그인 처리 흐름

측정 대상은 `POST /api/auth/login`이다.

1. 이메일로 회원 조회
2. BCrypt로 비밀번호 검증
3. 회원 상태 확인
4. 접근 토큰과 갱신 토큰 생성
5. Redis에 갱신 토큰 해시 저장
6. 로그인 응답과 갱신 토큰 쿠키 반환

소셜 로그인, 토큰 재발급, 로그아웃은 이번 반복 부하에 포함하지 않았다.

## 재현 준비

명령은 저장소 루트에서 실행한다.

### 1. 개인 인프라 확인

```bash
docker compose -f perf/order-list/compose.yaml ps

lsof -nP -iTCP:18090 -sTCP:LISTEN
lsof -nP -iTCP:18091 -sTCP:LISTEN
```

앱을 새로 실행하기 전에는 두 포트가 비어 있어야 한다.

최초 실험 준비 당시 Redis 논리 DB 1이 비어 있는 것을 확인했다.
기존 데이터가 있다면 소유와 용도를 확인하며, 일괄 삭제하지 않는다.

### 2. 최초 전용 DB 생성

이미 준비된 환경에서는 반복 실행할 필요가 없다.

```bash
docker compose -f perf/order-list/compose.yaml exec -T mysql \
mysql -uroot -pperf-local-only -e "
CREATE DATABASE IF NOT EXISTS attraction_auth_perf
CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

GRANT ALL PRIVILEGES
ON attraction_auth_perf.*
TO 'perf'@'%';
"
```

기존 실험 DB를 삭제하거나 덮어쓰지 않는다.
앱의 `ddl-auto: update` 설정으로 전용 DB의 테이블을 준비한다.

### 3. 빌드와 앱 실행

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"

bash gradlew bootJar --console=plain
python3 perf/auth-login/start-app.py
```

실행 스크립트는 `.local/app.log`와 `.local/app.pid`를 생성한다.
앱 재실행 시 로그 파일을 덮어쓰므로 필요한 로그는 먼저 보관한다.

빌드와 앱 실행은 측정 중에 수행하지 않는다.

### 4. 회원 준비

```bash
python3 perf/auth-login/prepare-users.py
```

전용 이메일은 `perfauth0000@example.test`부터 총 10개다.
비밀번호는 스크립트에 정의된 개인 실험용 공개 값만 사용한다.

기존 회원은 조건을 확인하고, 없는 회원만 가입시킨다.
회원 정보 검증 기준은 `.local/users.json`에 저장한다.
이 파일에는 비밀번호나 토큰을 저장하지 않는다.

MySQL 클라이언트 문자셋과 Python의 출력 해석은 UTF-8로 명시한다.

## 실제 API 정확성 검사

```bash
python3 perf/auth-login/check-login.py auth-login-smoke-01
```

결과 폴더 이름은 실행마다 새 이름을 사용한다.
검사 스크립트는 기존 결과 폴더 덮어쓰기를 허용하지 않는다.

확인 항목:

- 전용 회원 10명의 정상 로그인
- 발급된 접근 토큰으로 본인 정보 조회
- 토큰 종류와 유효 시간
- 응답 본문에 갱신 토큰이 없는지 확인
- 갱신 토큰 쿠키의 HttpOnly·Secure·SameSite·Path
- Redis 논리 DB 1에 저장된 갱신 토큰 해시 일치
- 잘못된 비밀번호 요청의 401 거절
- CSRF 누락 로그인 요청의 403 거절
- 미인증 본인 정보 조회의 401 거절

검사 결과는 모두 통과했다.

로컬 HTTP 검사에서는 Secure 쿠키를 요청에 명시적으로 전달했다.
서버의 쿠키 보안 설정을 낮추지는 않았다.
브라우저의 HTTPS 쿠키 동작까지 검증한 결과는 아니다.

## k6 실행 방법

아래 예시는 간단 검사 실행이다.
이미 사용한 결과 폴더 이름은 재사용하지 않는다.

```bash
mkdir perf/auth-login/results/auth-login-k6-smoke-01

cp perf/auth-login/auth-login.js \
perf/auth-login/results/auth-login-k6-smoke-01/auth-login.js

docker run --rm \
--user "$(id -u):$(id -g)" \
-v "$PWD/perf:/scripts:ro" \
-v "$PWD/perf/auth-login/results/auth-login-k6-smoke-01:/results" \
-e MODE=smoke \
-e VUS=1 \
grafana/k6@sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e \
run --out json=/results/metrics.json \
/scripts/auth-login/auth-login.js
```

- `MODE=smoke`: 가상 사용자마다 로그인 5회 실행
- `MODE=load`: 60초 동안 반복 실행
- `VUS`: 가상 사용자 수. 준비된 회원 수 이내로 설정

예열과 기준 측정에서는 새 결과 폴더를 사용하고
`MODE=load`, `VUS=10`으로 실행했다.

각 실행 폴더에 스크립트와 설정을 복사하고 다음 기록도 보관했다.

```bash
git rev-parse HEAD
shasum -a 256 build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar
```

세 기준 측정의 소스 커밋 기록·JAR 해시 기록·설정·스크립트가
모두 같은 것을 확인했다.
JAR 해시는 디스크의 실행 파일에 대한 기록이며,
실행 중인 프로세스와 자동 대조하는 기능은 아니다.

## 부하 구성과 지표

가상 사용자마다 다른 회원을 배정한다.
같은 회원의 갱신 토큰 저장 키를 여러 가상 사용자가 공유하는 상황을 피했다.

각 가상 사용자는 첫 반복에서 CSRF를 준비하고,
이후 로그인 요청을 반복한다.
로그인이 끝나면 1초간 대기한다.

| 지표 | 의미 |
|---|---|
| auth_login_attempts | 로그인 시도 수 |
| auth_login_successes | 응답 검사까지 통과한 로그인 수 |
| auth_login_failed | 로그인 응답 검사 실패율 |
| auth_login_duration | 로그인 요청의 응답 시간 |
| checks | CSRF 준비 및 로그인 응답 검사 통과율 |

`auth_login_duration`에는 CSRF 준비 요청과 1초 대기를 포함하지 않는다.
DNS 조회와 연결 준비 시간도 포함하지 않는다.

기본 HTTP 지표에는 CSRF 준비 요청이 포함되므로,
로그인 응답 시간 비교에는 전용 지표를 사용한다.

기준 실행 1회당 검사는 로그인 7개와 가상 사용자별 CSRF 준비 1개다.

```text
전체 검사 수 = 로그인 수 × 7 + 가상 사용자 수
```

실패율 지표의 `passes`와 `fails`는 기록한 참·거짓 값의 개수다.
로그인 실패 여부에 거짓을 기록하면 `fails`가 증가한다.
실제 실패율은 `auth_login_failed.values.rate`로 확인한다.

정확성 기준은 로그인 실패율 0%, 검사 통과율 100%다.
서비스 요구사항에 근거한 응답 시간 목표는 이번 실험에서 정하지 않았다.

## 실행 순서와 결과

1. 실제 API 정확성 검사
2. 1 VU 간단 검사
3. 10 VU·60초 예열 1회
4. 같은 조건의 기준 측정 3회
5. 회원 조회 실행 계획 확인
6. 별도 JFR 진단 실행

### 예열

- 로그인 530건 성공
- 평균 132.95ms
- P95 246.26ms
- 실패율 0%

예열 결과는 기준 측정에 포함하지 않았다.

### 기준 측정

| 항목 | 1회차 | 2회차 | 3회차 |
|---|---:|---:|---:|
| 로그인 성공 | 550건 | 540건 | 540건 |
| 평균 응답 시간 | 106.48ms | 111.10ms | 119.30ms |
| P95 | 125.87ms | 123.72ms | 170.74ms |
| P99 | 145.59ms | 132.44ms | 257.40ms |
| 로그인 실패율 | 0% | 0% | 0% |
| 응답 검사 통과 | 3,860개 | 3,790개 | 3,790개 |

총 로그인 1,630건이 모두 성공했고 검사 11,440개가 통과했다.

- 실행별 평균 응답 시간의 중앙값: 111.10ms
- 실행별 P95의 중앙값: 125.87ms

이는 모든 요청을 합쳐 계산한 평균이나 P95가 아니다.
3회차의 P95·P99 증가 원인은 이 측정만으로 확정하지 않았다.

초당 성공 수는 요청 간 대기와 실행 종료 시간을 포함한 관측값이다.
서버의 최대 처리량으로 해석하지 않는다.

## 회원 조회 실행 계획

전용 DB에 회원 10명이 있는 상태에서 확인했다.

```sql
EXPLAIN ANALYZE
SELECT *
FROM users
WHERE email = 'perfauth0000@example.test';
```

- 이메일에 고유 인덱스 `uk_users_email`이 존재했다.
- 실행 계획에는 `Rows fetched before execution`, 실제 반환 1행이 표시됐다.
- 표시된 시간을 로그인 전체의 DB 조회 시간으로 해석하지 않았다.
- 대규모 회원 데이터에서의 조회 성능을 검증한 결과는 아니다.
- 추가 인덱스는 적용하지 않았다.

## JFR 진단

기준 측정과 별도로 10 VU·60초 부하를 실행했다.

실행 중인 앱에 다음 설정으로 JFR 기록을 시작했다.

```bash
perf_auth_pid=$(cat perf/auth-login/.local/app.pid)

"$JAVA_HOME/bin/jcmd" "$perf_auth_pid" JFR.start \
name=auth_login_diagnostic_01 \
settings=profile \
duration=120s \
maxsize=64m \
filename="$PWD/perf/auth-login/results/auth-login-diagnostic-01/login-profile.jfr"
```

기록 시작 직후 부하를 실행하고, 완료 후 기록을 종료·저장했다.
재현할 때는 새 결과 폴더와 기록 이름을 사용한다.

진단 실행 결과:

- 로그인 540건 모두 성공
- 검사 3,790개 통과
- 평균 122.06ms / P95 176.39ms
- JFR 파일 저장 완료

진단 도구의 영향을 고려해 기준 측정과 별도로 취급했다.

### 실행 표본 분석

`jdk.ExecutionSample` 이벤트를 최대 스택 깊이 64로 출력하고,
스택에 BCrypt 클래스가 포함된 이벤트를 한 번씩 집계했다.

| 항목 | 결과 |
|---|---:|
| 전체 실행 표본 | 1,364개 |
| BCrypt 포함 실행 표본 | 1,284개 |
| BCrypt 포함 표본 비율 | 94.13% |

분석 원본은 `login-profile.jfr`,
요약은 `profile-summary.txt`로 보관했다.

이 비율은 전체 응답 시간이나 CPU 사용률의 비율이 아니다.
DB·네트워크 대기와 전체 요청 시간을 직접 분해한 결과도 아니다.

이번 기록에서 BCrypt 계산이 주요 실행 경로로 관측됐다는 의미다.
CPU 포화나 최대 수용량은 확정하지 않았다.

## 최종 변경 판단

- BCrypt의 비밀번호 검증 강도를 유지했다.
- 비밀번호 검증 결과를 캐시하거나 검증을 생략하지 않았다.
- DB 인덱스와 연결 풀 크기를 변경하지 않았다.
- 로그인 서비스의 성능 변경을 적용하지 않았다.

비밀번호 검증에 계산 비용이 드는 것은 보안 설계의 일부다.
실행 표본 비율만으로 검증 강도를 낮출 근거는 되지 않는다.

## 결과 보관

- `.local/users.json`: 회원 검증 기준
- `results/*/summary.json`: k6 실행 요약
- `results/*/metrics.json`: k6 원본 지표
- `results/*/verification.json`: 정확성 검사 결과
- `results/diagnostic-01/user-lookup-plan.txt`: 회원 조회 실행 계획
- `results/auth-login-diagnostic-01/login-profile.jfr`: JFR 원본
- `results/auth-login-diagnostic-01/profile-summary.txt`: 표본 분석 요약

검사 도구는 토큰·쿠키·비밀번호 원문을 결과 파일에 기록하지 않는다.
공개 실험용 비밀번호는 준비 스크립트와 k6 스크립트에 정의돼 있다.

원본 결과는 Git에서 제외하고 별도 압축 파일로 백업했다.
JFR에는 실행 환경 정보가 포함될 수 있으므로 원본 공유 전 내용을 확인한다.

## 검증 및 한계

- AuthControllerTest·AuthServiceTest·JwtProviderTest 실행 통과
- 실제 API 정확성 검사 통과
- k6 간단 검사 통과
- 예열 및 기준 측정 3회 통과
- 별도 JFR 진단 실행 통과

한계:

- 회원 10명을 사용하는 개인 환경 실험이다.
- 데이터가 많은 상태의 로그인 성능을 검증하지 않았다.
- 소셜 로그인·토큰 재발급·로그아웃 부하는 포함하지 않았다.
- 실패 로그인 반복이나 공격 상황을 부하로 측정하지 않았다.
- 브라우저의 실제 HTTPS 쿠키 처리를 검증한 결과가 아니다.
- 장시간 실행, 급격한 요청 증가, 최대 처리량은 확인하지 않았다.
- 배포 환경의 동시 사용자 2,000명 수용 여부를 증명하지 않는다.