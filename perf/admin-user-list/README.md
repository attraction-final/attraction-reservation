# 관리자 회원 목록 부하 테스트 및 복합 인덱스 실험

## 목적

`GET /api/admin/users`의 접근 권한과 응답 정확성을 확인하고, 회원 데이터가 많을 때 최신순 목록 조회와 전체 건수 계산에 드는 비용을 측정한다.

실행 계획을 바탕으로 복합 인덱스를 추가한 뒤 같은 부하 조건으로 반복 측정한다.

- 실제 HTTP 부하와 측정: Docker k6
- 인증·검증 기준 준비와 결과 보관: Python 보조 스크립트
- 적용 범위: 개인 테스트 환경
- 애플리케이션 조회 코드 변경과 공유 DB 적용은 포함하지 않음

## 조회 방식

- 관리자만 접근 가능
- 탈퇴 상태인 `WITHDRAWN` 회원 제외
- `created_at DESC, id DESC` 정렬
- 페이지당 10명
- 회원 목록과 전체 건수·페이지 정보 반환

## 환경과 데이터

- 개인 Mac에서 Spring Boot 실행
- MySQL·Redis·k6는 Docker 실행
- 기존 `../order-list`의 개인 테스트 환경과 보조 코드 재사용
- 앱 코드 기준: `49b39a1`로 수동 기록
- 실행 바이너리의 커밋을 자동 검증한 값은 아님
- 전후 본 측정에서 같은 앱 PID `61532` 유지

기존 일반 회원 2,000명과 별도 관리자 1명에 조회용 합성 회원 100,000명을 추가했다.

| 추가 합성 회원 상태 | 인원 |
|---|---:|
| ACTIVE | 70,000명 |
| INACTIVE | 10,000명 |
| SUSPENDED | 10,000명 |
| WITHDRAWN | 10,000명 |

- 전체 회원: 102,001명
- 탈퇴 제외 조회 대상: 92,001명
- 전체 페이지: 9,201개
- 조회 페이지: 0·4,600·9,200
- 마지막 페이지 반환 건수: 1명

이 분포는 조회와 필터링 검증을 위한 실험 조건이며 실제 서비스의 회원 분포를 대표하지 않는다.

## 부하 조건

- 10 VU가 동일한 관리자 계정 1개 사용
- 첫·중간·마지막 페이지를 각 VU가 순환 조회
- 본 측정은 실행별 60초
- 응답 후 1초 대기
- 일정한 VU 수를 유지하는 닫힌 부하 모델
- 적용 전·후 각각 준비 실행 1회와 본 측정 3회
- 준비 실행은 전후 비교에서 제외

10 VU는 요청 부하 조건이다. 서로 다른 관리자 10명이나 실제 관리자 이용 패턴을 재현한 것은 아니다.

## 접근 권한과 응답 검증

`check-access.py`에서 다음을 확인한다.

- 미인증 요청: 401
- 일반 회원 요청: 403
- 관리자 요청: 200 및 업무 성공
- 첫 페이지의 회원 ID·정렬 순서·페이지 정보·전체 건수
- 반환 목록에 탈퇴 회원이 포함되지 않음

k6는 각 정상 조회에서 다음 8개 항목을 검증한다.

- HTTP 200
- 업무 성공
- 페이지 번호와 크기
- 전체 건수와 페이지 수
- 페이지별 회원 수
- 회원 ID와 정렬 순서
- ID·이메일·닉네임·권한·상태
- 응답 필드 구성과 생성 시각의 문자열 여부

생성 시각의 값 자체를 DB와 대조하는 검증은 포함하지 않는다. 정렬 결과는 DB에서 조회한 회원 ID 순서와 비교한다.

통과 기준은 응답 검증 실패와 HTTP 실패가 0인 것이다. 응답 시간에 대한 운영 성능 합격 기준은 설정하지 않았다.

## 파일 구성

| 파일 | 역할 |
|---|---|
| check-access.py | 권한과 첫 페이지 응답 검사 |
| prepare-data.sql | 조회용 합성 회원 100,000명 추가 |
| prepare-admin.py | 관리자 로그인과 세 페이지의 검증 기준 준비 |
| admin-user-list.js | k6 요청·검증·전체 및 페이지별 응답 시간 측정 |
| run-admin.py | 준비 작업, k6 실행, 조건과 원본 결과 보관 |
| index-admin-user-list.sql | 개인 DB에 적용할 인덱스 SQL |
| .gitignore | 토큰·원본 결과·캐시의 Git 추적 제외 |

## 실행 준비

저장소 루트에서 실행한다. macOS, Java 17, Python 3, Docker Desktop을 사용한다.

개인 환경의 최초 구성·앱 실행·기본 회원과 주문 데이터 준비는 `../order-list/README.md`를 따른다. 기존 2,000명 회원 풀 준비 SQL은 `../user-me/prepare-load-users.sql`을 사용했다.

이미 실행 중인 비교 대상 앱이 있다면 실험 중 임의로 다시 빌드하거나 재시작하지 않는다. 새로운 실험에서는 실제 코드·JAR·Java 버전·서버 설정을 별도로 기록한다.

### 테스트 관리자 계정

`perfadmin0000@example.test`인 ACTIVE·LOCAL·ADMIN 계정이 필요하다. 기존 테스트 계정의 비밀번호 해시를 복사해 생성했으며, 일반 회원의 권한은 변경하지 않았다.

개인 `attraction_perf` DB에서만 다음 SQL을 실행한다.

```sql
INSERT INTO users (
    created_at, updated_at, email, password, nickname,
    role, status, provider, provider_user_id, withdrawn_at
)
SELECT
    NOW(6), NOW(6),
    'perfadmin0000@example.test',
    template.password,
    '성능테스트관리자',
    'ADMIN', 'ACTIVE', 'LOCAL', NULL, NULL
FROM users AS template
WHERE template.email = 'perf0000@example.test'
  AND template.provider = 'LOCAL'
  AND template.status = 'ACTIVE'
  AND template.password IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM users
      WHERE email = 'perfadmin0000@example.test'
  );
```

기존 관리자 계정이 있으면 새로 추가하지 않는다. 생성 후 이메일·권한·상태를 확인한다.

### 조회용 데이터 추가

```bash
docker compose -f perf/order-list/compose.yaml exec -T mysql \
mysql -uperf -pperf-local-only attraction_perf \
< perf/admin-user-list/prepare-data.sql
```

같은 합성 이메일이 있으면 중복 추가하지 않는다. 기존 회원·주문 데이터는 변경하지 않는다.

## 실행 순서

### 권한·응답 검사

```bash
python3 perf/admin-user-list/check-access.py
```

### 소규모 검사

```bash
python3 perf/admin-user-list/prepare-admin.py
mkdir -p perf/admin-user-list/results/smoke-new-01

docker run --rm \
--user "$(id -u):$(id -g)" \
-v "$PWD/perf:/scripts:ro" \
-v "$PWD/perf/admin-user-list/results/smoke-new-01:/results" \
-e MODE=smoke \
-e VUS=1 \
grafana/k6@sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e \
run --out json=/results/metrics.json \
/scripts/admin-user-list/admin-user-list.js
```

소규모 모드는 1 VU가 6회 조회한다. 세 페이지를 두 번씩 확인하며, 정상 조회에 대한 검증은 총 48개다.

### 적용 전 반복 측정

```bash
python3 perf/admin-user-list/run-admin.py warmup-new-01 --phase warmup --vus 10
python3 perf/admin-user-list/run-admin.py baseline-new-01 --phase baseline --vus 10
python3 perf/admin-user-list/run-admin.py baseline-new-02 --phase baseline --vus 10
python3 perf/admin-user-list/run-admin.py baseline-new-03 --phase baseline --vus 10
```

각 명령이 정상 완료된 뒤 다음 명령을 실행한다. 실행 ID는 중복 사용할 수 없다.

인덱스가 이미 적용된 환경에서는 위 명령에 `baseline`이라는 이름을 붙여도 적용 전 상태가 되지 않는다. `--phase`는 기록용 구분이며 DB 상태를 변경하지 않는다.

### 인덱스 적용과 적용 후 측정

기존 인덱스를 확인하고, 같은 이름의 인덱스가 없을 때 개인 DB에 한 번 적용한다.

```bash
docker compose -f perf/order-list/compose.yaml exec -T mysql \
mysql -uperf -pperf-local-only attraction_perf \
< perf/admin-user-list/index-admin-user-list.sql
```

권한·응답 검사를 다시 수행한 뒤 적용 전과 같은 준비·측정 절차를 사용한다.

```bash
python3 perf/admin-user-list/check-access.py

python3 perf/admin-user-list/run-admin.py index-warmup-new-01 --phase warmup --vus 10
python3 perf/admin-user-list/run-admin.py index-after-new-01 --phase after --vus 10
python3 perf/admin-user-list/run-admin.py index-after-new-02 --phase after --vus 10
python3 perf/admin-user-list/run-admin.py index-after-new-03 --phase after --vus 10
```

측정 사이에 데이터·앱 코드·서버 설정을 바꾸거나 다른 부하 테스트를 함께 실행하지 않는다.

## 인덱스 가설과 실행 계획

실험 인덱스:

```sql
CREATE INDEX idx_users_created_id_status
ON users (created_at DESC, id DESC, status);
```

조회 결과의 정렬 순서를 먼저 맞추고 상태 컬럼도 함께 보관하도록 구성했다. 상태 조건은 한 상태의 동등 비교가 아니라 탈퇴 상태를 제외하는 조건이다.

| 조회 | 적용 전 | 적용 후 |
|---|---|---|
| 첫 페이지 | 전체 스캔과 별도 정렬 | 새 인덱스로 10행 읽기, 별도 정렬 없음 |
| 중간 페이지 | 전체 스캔과 별도 정렬 | 전체 스캔과 별도 정렬 유지 |
| 마지막 페이지 | 전체 스캔과 별도 정렬 | 전체 스캔과 별도 정렬 유지 |
| 전체 건수 | 테이블 전체 스캔 | 새 인덱스의 커버링 스캔 |

전체 건수 계산은 인덱스 항목 102,001개를 확인했다. 테이블 대신 인덱스를 읽게 된 것이며 확인하는 항목 수가 줄어든 것은 아니다.

개별 SQL의 단일 실행 시간으로 개선율을 계산하지 않았다. 인덱스를 강제로 사용하도록 조회 코드를 바꾸지 않았다.

## API 본 측정 결과

| 조건·실행 | 목록 조회 수 | 평균 | P95 | P99 | 응답 검증 실패 |
|---|---:|---:|---:|---:|---:|
| 적용 전 01 | 540 | 126.99ms | 168.02ms | 229.10ms | 0 |
| 적용 전 02 | 539 | 129.97ms | 256.65ms | 475.89ms | 0 |
| 적용 전 03 | 540 | 111.62ms | 143.69ms | 156.00ms | 0 |
| 적용 후 01 | 560 | 86.08ms | 126.55ms | 133.33ms | 0 |
| 적용 후 02 | 560 | 87.58ms | 129.81ms | 146.48ms | 0 |
| 적용 후 03 | 560 | 86.44ms | 127.31ms | 134.03ms | 0 |

최종 여섯 실행 모두 HTTP 실패와 중단된 반복 없이 완료됐다. 적용 전 실행 02의 높은 지연도 제외하지 않고 비교에 포함했다.

### 실행별 지표의 중앙값 비교

| 지표 | 적용 전 | 적용 후 | 감소율 |
|---|---:|---:|---:|
| 전체 평균 응답 시간의 중앙값 | 126.99ms | 86.44ms | 약 31.9% |
| 전체 P95의 중앙값 | 168.02ms | 127.31ms | 약 24.2% |
| 첫 페이지 P95의 중앙값 | 134.04ms | 43.30ms | 약 67.7% |

각 조건의 세 실행에서 얻은 지표의 중앙값이다. 모든 요청을 합쳐 계산한 평균이나 P95가 아니며, 감소율은 반올림 전 원본 값으로 계산했다.

전체 응답 시간은 `admin_list_duration`을 사용한다. 공통 HTTP 지표에는 미인증 사전 검사 1건도 포함된다.

## 판단과 한계

첫 페이지에서 전체 스캔·정렬이 인덱스를 이용한 소량 조회로 바뀌었고, API 응답 시간도 낮게 관측됐다.

중간·마지막 페이지에서는 정렬과 OFFSET 처리 비용이 남았다. 해당 페이지의 응