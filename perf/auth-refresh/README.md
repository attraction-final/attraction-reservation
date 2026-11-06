# 토큰 재발급 정확성 검증과 기준 부하 측정

## 1. 실험 질문과 결과

기존 갱신 토큰을 사용해 접근 토큰을 재발급할 때 다음을 확인했다.

- 발급된 접근 토큰으로 본인 정보를 조회할 수 있는가?
- 재발급 과정에서 Redis의 갱신 토큰 해시와 절대 만료 시각이 유지되는가?
- 누락·잘못된 종류·무효화된 토큰과 CSRF 누락 요청이 거절되는가?
- 작은 반복 부하에서 응답 시간과 오류율은 어떻게 관측되는가?

실제 API 정확성 검사가 통과했다.
이후 10 VU·60초 기준 측정 3회에서 재발급 1,770건과 검사 12,450개가 모두 통과했다.

이번 작업은 정확성 검증과 초기 기준 측정이다.
서비스 코드·인덱스·캐시·연결 풀 설정의 성능 변경은 적용하지 않았다.

## 2. 현재 재발급 정책

대상 API는 `POST /api/auth/refresh`다.

1. 갱신 토큰의 JWT 서명·만료·토큰 종류 검증
2. Redis에 저장된 갱신 토큰 해시와 대조
3. 회원 존재 여부와 ACTIVE 상태 확인
4. 접근 토큰 발급

현재 구현은 갱신 토큰을 교체하지 않고 접근 토큰만 발급한다.
재발급 성공 시 Redis의 기존 해시와 절대 만료 시각도 유지한다.

이 정책을 검사한 것이며, 갱신 토큰 교체 방식으로 변경한 작업은 아니다.

## 3. 실험 조건과 선택 이유

| 항목 | 조건과 이유 |
|---|---|
| 코드 기준 | be8870d |
| 실행 환경 | 개인 Mac에서 앱·DB·부하 발생기 실행 |
| Java | Corretto OpenJDK 17.0.19 |
| 앱 힙 | 최소 256MB / 최대 512MB |
| 앱·관리 포트 | 18090 / 18091 |
| 전용 MySQL DB | attraction_auth_perf |
| Redis 논리 DB | 1 |
| DB 연결 풀 최대 크기 | 10 |
| 회원 | 기존 로그인 실험의 ACTIVE·USER·LOCAL 회원 10명 재사용 |
| 계정 배정 | VU마다 다른 회원 사용 |
| 부하 | 10 VU·60초, 재발급 응답 후 1초 대기 |
| 측정 순서 | 정확성 검사 → k6 간단 검사 → 예열 → 기준 측정 3회 |

같은 회원의 갱신 토큰 키를 여러 VU가 로그인으로 덮어쓰지 않도록 계정을 분리했다.
로그인 실험과 재발급 실험은 동시에 실행하지 않았다.

매초 재발급하는 조건은 실제 사용자의 토큰 사용 주기를 재현한 것이 아니다.
재발급 처리 경로의 초기 기준값을 얻기 위한 탐색용 반복 부하다.

앱·MySQL·Redis·k6는 같은 Mac의 자원을 공유한다.
Redis 논리 DB 분리는 키 공간 분리이며 CPU·메모리 분리는 아니다.

설정 파일과 스크립트의 인증 정보는 개인 실험용 공개 값이다.
실제 서비스의 비밀값을 넣지 않는다.

## 4. 파일별 역할

| 파일 | 역할 |
|---|---|
| application-perf.yaml | 전용 DB·Redis·포트·스케줄러 설정 |
| start-app.py | Java 17 확인과 개인 테스트 앱 실행 |
| prepare-users.py | 회원 준비·기존 회원 조건 확인·검증 기준 저장 |
| check-refresh.py | 실제 API와 Redis를 이용한 정확성 검사 |
| auth-refresh.js | k6 간단 검사와 60초 반복 부하 |
| .gitignore | 준비 파일·원본 결과의 Git 제외 |

설정·앱 실행·회원 준비 파일은 로그인 실험 커밋 `18ac30d`에서 재사용했다.
실험 도구를 가져온 커밋과 이번 실행 앱의 코드 기준은 구분한다.

## 5. 실제 API 정확성 검사

회원 10명이 각각 로그인한 뒤 같은 갱신 토큰으로 두 번 재발급했다.
총 20번의 재발급에서 다음을 확인했다.

- HTTP 200과 업무 성공 응답
- 접근 토큰 종류와 유효 시간 600초
- 발급된 토큰으로 본인 정보 조회 성공
- 반환된 회원 정보가 검증 기준과 일치
- 응답 본문에 갱신 토큰 원문이 없음
- 재발급 응답에서 갱신 토큰 쿠키가 새로 발급되지 않음
- Redis의 갱신 토큰 해시가 로그인 때 받은 토큰의 SHA-256과 일치
- 두 번의 재발급 전후 Redis 해시와 절대 만료 시각이 동일

Redis의 남은 유효기간은 시간이 지나면서 감소한다.
따라서 만료 연장 여부는 `PEXPIRETIME`으로 조회한 절대 만료 시각을 비교했다.

거절 검사 결과:

| 조건 | 결과 |
|---|---:|
| 갱신 토큰 쿠키 누락 | 401 |
| 잘못된 형식의 갱신 토큰 | 401 |
| 접근 토큰을 갱신 토큰으로 전달 | 401 |
| CSRF 누락 | 403 |
| 로그아웃으로 무효화된 갱신 토큰 | 401 |

앞의 네 가지 거절 요청 이후에도 기존 Redis 해시와 만료 시각이 유지되는지 확인했다.

마지막 검사에서는 전용 회원 1명을 실제 로그아웃시켰다.
Redis 키가 삭제된 뒤 기존 갱신 토큰의 재발급이 거절되는지 확인했다.
이는 무효화 검사의 준비 동작이며 로그아웃 부하 측정은 아니다.

정확성 검사 종료 시 전용 회원 한 명은 로그아웃 상태로 남는다.
이후 k6 실행에서 각 VU가 로그인하므로 해당 회원의 인증 정보도 다시 준비된다.

## 6. 반복 부하에서 확인한 범위

각 VU의 첫 반복에서 CSRF 준비와 로그인을 한 번 수행한다.
이후 갱신 토큰을 메모리에 보관하고 재발급을 반복한다.

매 재발급 응답에서 다음 7개 항목을 검사한다.

1. HTTP 200
2. 업무 성공
3. 접근 토큰 반환
4. Bearer 및 응답 유효 시간 600초
5. JWT 본문의 회원 ID와 ACCESS 종류
6. JWT 본문의 토큰 수명 600초
7. 본문·쿠키를 통한 갱신 토큰 재발급 없음

JWT 본문 디코딩은 서명 검증이 아니다.
실제 토큰 사용 가능 여부는 별도 정확성 검사에서 본인 정보 조회로 확인했다.

반복 부하에서는 요청마다 본인 정보 API와 Redis 조회를 추가하지 않았다.
따라서 부하 구간의 모든 응답에 대해 서명 검증이나 Redis 상태 유지를
개별 확인했다고 표현하지 않는다.

## 7. 측정 지표

| 지표 | 의미 |
|---|---|
| auth_refresh_attempts | 재발급 시도 수 |
| auth_refresh_successes | 응답 검사까지 통과한 재발급 수 |
| auth_refresh_failed | 재발급 응답 검사 실패율 |
| auth_refresh_duration | 재발급 요청의 응답 시간 |
| checks | 준비 검사와 재발급 응답 검사 통과율 |

`auth_refresh_duration`에는 CSRF·로그인 준비 요청과 1초 대기를 포함하지 않는다.
DNS 조회와 연결 준비 시간도 포함하지 않는다.

다만 준비 요청도 같은 서버에서 실행된다.
실행 초반의 부하와 전체 실행 시간에는 준비 과정이 영향을 줄 수 있다.

기본 HTTP 지표에는 CSRF와 로그인 요청도 포함된다.
재발급 응답 시간 비교에는 전용 지표를 사용한다.

기준 실행당 검사 수:

```text
재발급 590건 × 7개 + 회원 10명의 준비 검사 × 2개 = 4,150개
```

실패율 지표에서 `fails`는 거짓 값이 기록된 개수다.
`auth_refresh_failed.values.rate`가 0이면 해당 지표의 실패율은 0%다.

## 8. 측정 결과

### 예열

- 재발급 580건 모두 성공
- 검사 4,080개 모두 통과
- 평균 28.55ms / P95 61.79ms
- 기준 측정에서 제외

### 기준 측정 3회

| 항목 | 1회차 | 2회차 | 3회차 |
|---|---:|---:|---:|
| 재발급 성공 | 590건 | 590건 | 590건 |
| 평균 응답 시간 | 23.65ms | 21.54ms | 22.24ms |
| P95 | 34.73ms | 32.56ms | 33.50ms |
| P99 | 41.09ms | 52.17ms | 83.56ms |
| 재발급 실패율 | 0% | 0% | 0% |
| 검사 통과 | 4,150개 | 4,150개 | 4,150개 |

총 재발급 1,770건과 검사 12,450개가 모두 통과했다.
세 실행 모두 HTTP 실패와 중단된 반복은 없었다.

- 실행별 평균의 중앙값: 22.24ms
- 실행별 P95의 중앙값: 33.50ms

이는 전체 요청을 합쳐 계산한 평균이나 P95가 아니다.

각 실행에 보관한 소스 커밋 기록·JAR 해시 기록·앱 설정·스크립트가
동일하고 비어 있지 않은 것을 확인했다.
실행 중 프로세스와 디스크의 JAR을 자동 대조한 것은 아니다.

## 9. 결과 해석과 한계

이번 조건에서는 정상 재발급 요청이 모두 성공했다.
P95는 실행별 32.56~34.73ms였고, P99는 41.09~83.56ms로 차이가 있었다.

P99 차이의 원인은 이번 자료만으로 확정하지 않았다.
CPU·DB 연결 대기·Redis 대기 등을 별도로 추적한 실험은 아니다.

서비스 응답 시간 목표를 정하지 않았으므로 “충분히 빠르다”는 판정은 하지 않았다.
현재 수치만으로 병목이 없거나 구현이 최적이라고 결론 내리지 않았다.

추가 해석 범위:

- 실제 트래픽에서 도출한 부하 조건이 아니다.
- 매초 반복하는 조건은 실제 재발급 주기를 대표하지 않는다.
- 회원 10명을 사용하며 최대 처리량을 측정하지 않았다.
- 동시 요청 2,000건이나 배포 서버 수용 능력을 검증하지 않았다.
- 로컬 HTTP에서 Secure 쿠키를 명시적으로 전달했다.
- 실제 브라우저의 HTTPS 쿠키 동작을 검증한 결과는 아니다.
- 별도 실제 API 검사에서 만료 JWT와 비활성 회원의 모든 조합을 검사한 것은 아니다.
- 장시간 부하와 재발급·로그아웃 간 동시 경합은 검증하지 않았다.
- 일반 로그인 결과와의 차이를 성능 개선율로 계산하지 않는다.

이번 작업에서는 서비스 코드와 설정의 성능 변경을 적용하지 않았다.

## 10. 재현 방법

저장소 루트에서 실행한다.

기존 개인 MySQL·Redis와 `attraction_auth_perf` DB를 사용한다.
최초 환경 구성은 `perf/auth-login/README.md`의 전용 DB 준비 절차와 동일하다.
로그인 문서가 없는 브랜치에서는 원본 커밋 `18ac30d`를 참고한다.

Redis 1번 공간의 용도를 확인하며 기존 데이터를 일괄 삭제하지 않는다.

### 앱 준비

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"

bash gradlew bootJar --console=plain
python3 perf/auth-refresh/start-app.py
```

18090·18091 포트를 사용하는 기존 앱이 있다면 먼저 확인하고 종료한다.
실행 스크립트는 `.local/app.log`를 덮어쓰므로 필요한 로그는 먼저 보관한다.

### 회원과 정확성 검사

```bash
python3 perf/auth-refresh/prepare-users.py
python3 perf/auth-refresh/check-refresh.py auth-refresh-smoke-01
```

기존 결과 폴더 이름은 재사용하지 않는다.
정확성 검사 결과는 `verification.json`에 저장된다.

### k6 실행

아래는 기준 측정 1회차 재현 예시다.
기존 결과가 있다면 새 실행 이름을 사용한다.

```bash
mkdir perf/auth-refresh/results/auth-refresh-baseline-01

cp perf/auth-refresh/auth-refresh.js \
perf/auth-refresh/results/auth-refresh-baseline-01/auth-refresh.js

cp perf/auth-refresh/application-perf.yaml \
perf/auth-refresh/results/auth-refresh-baseline-01/application-perf.yaml

git rev-parse HEAD \
> perf/auth-refresh/results/auth-refresh-baseline-01/source-commit.txt

shasum -a 256 build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar \
> perf/auth-refresh/results/auth-refresh-baseline-01/app-jar-sha256.txt

docker run --rm \
--user "$(id -u):$(id -g)" \
-v "$PWD/perf:/scripts:ro" \
-v "$PWD/perf/auth-refresh/results/auth-refresh-baseline-01:/results" \
-e MODE=load \
-e VUS=10 \
grafana/k6@sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e \
run --out json=/results/metrics.json \
/scripts/auth-refresh/auth-refresh.js
```

- `MODE=smoke`: 각 가상 사용자가 5회 실행
- `MODE=load`: 60초 반복 실행
- `VUS`: 준비된 회원 수 이내의 가상 사용자 수

예열은 별도 결과 폴더에서 `MODE=load`, `VUS=10`으로 1회 수행했다.
이후 앱·설정·스크립트를 유지하고 기준 측정을 3회 수행했다.

## 11. 검증과 결과 보관

실행한 기존 테스트:

- AuthControllerTest
- AuthServiceTest
- AuthRefreshIntegrationTest

별도 통합 테스트는 Testcontainers의 MySQL·Redis를 사용했다.
개인 부하 실험 DB와 구분한다.

원본 결과 폴더:

- auth-refresh-smoke-01
- auth-refresh-k6-smoke-01
- auth-refresh-warmup-01
- auth-refresh-baseline-01
- auth-refresh-baseline-02
- auth-refresh-baseline-03

보관 파일:

- verification.json: 실제 API 정확성 검사 결과
- summary.json: k6 실행 요약
- metrics.json: 시간대별 원본 지표
- 코드·설정 사본과 소스 커밋·JAR 해시 기록

`.local/`과 `results/`는 Git에서 제외한다.
토큰·쿠키 원문은 메모리에서만 사용하며 결과 파일에 저장하지 않는다.
공개 실험용 비밀번호는 준비 스크립트와 k6 스크립트에 정의돼 있다.

원본 결과는 `auth-refresh-baseline-20261106.tar.gz`로 별도 백업했다.
같은 Mac에 보관한 압축 사본이며 외부 기기 백업을 의미하지 않는다.