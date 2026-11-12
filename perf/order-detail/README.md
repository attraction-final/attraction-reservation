# 주문 상세 조회 부하 테스트

## 목적

`GET /api/orders/{orderId}`의 응답 정확성과 응답 시간을 확인한다.
병목 근거가 확인된 경우에만 추가 개선을 검토한다.

## 실행 환경

- 개인 Mac에서 Spring Boot 실행
- Docker에서 MySQL·Redis·k6 실행
- 기존 `perf/order-list`의 개인 테스트 환경과 공통 Python 함수를 재사용
- Python은 데이터 준비·실행·자료 보관에 사용하며 실제 부하는 k6가 발생시킴
- 공유 서버와 공유 DB를 대상으로 실행하지 않음

기존 주문 목록 테스트 환경과 회원 100명·회원별 주문 1,000건이
준비된 상태를 전제로 한다. 환경 구성은 `../order-list/README.md`를 참고한다.
아래 명령어는 저장소 루트에서 실행한다.

## 데이터 준비

```bash
docker compose -f perf/order-list/compose.yaml exec -T mysql \
mysql -uperf -pperf-local-only attraction_perf \
< perf/order-detail/prepare.sql

python3 perf/order-detail/prepare-detail.py
```

회원별 주문 2건을 선택해 상품 1종·3종 주문을 준비한다.
예상 결과는 회원 100명·대상 주문 200건·주문 항목 400건이다.
각 주문의 항목 합계는 기존 주문 금액인 10,000원과 일치해야 한다.

조회용 합성 데이터이며 주문 생성·재고·결제·티켓 발급 검증에는 사용하지 않는다.
기존 주문 항목이 있는 대상 주문은 추가를 건너뛰므로,
다른 데이터 상태에서는 검증 결과를 반드시 확인한다.

## 부하 실행

```bash
python3 perf/order-detail/run-detail.py detail-warmup-01
python3 perf/order-detail/run-detail.py detail-baseline-01
python3 perf/order-detail/run-detail.py detail-baseline-02
python3 perf/order-detail/run-detail.py detail-baseline-03
```

각 실행이 끝난 뒤 다음 명령어를 실행한다.
기본 조건은 100 VU·60초·응답 후 1초 대기다.
준비 실행은 본 측정에서 제외한다.

실행 도구는 검증 기준과 로그인 토큰을 갱신한 뒤 k6를 실행한다.
기존 결과를 덮어쓰지 않으므로 재측정할 때는 새로운 실행 ID를 사용한다.

## 검증

- 미인증 접근 401, 다른 회원 접근 403
- 정상 응답 HTTP 200 및 업무 성공
- 주문 ID·금액·상태 일치
- 주문 항목 ID·상품 ID·단가·수량 일치
- 주문 금액과 항목 합계 일치

항목 순서는 보장 조건으로 두지 않고 ID로 정렬한 뒤 비교한다.
생성 시각은 현재 스크립트의 검증 대상에 포함하지 않는다.

## 결과 보관

`results/<실행 ID>/`에 다음 자료를 저장한다.

- `metadata.json`: 실행 조건·시각·도구 커밋·종료 코드
- `summary.json`, `metrics.json`: k6 측정값
- `dashboard.html`, `terminal.txt`: 보고서와 실행 출력
- `fixture.json`, `order-detail.js`: 검증 기준과 스크립트 사본
- `db-before.txt`: 데이터 규모·인덱스 정보
- `sql-digests-before.txt`, `sql-digests-after.txt`: SQL 누적 통계
- `resource-samples.jsonl`: 앱·컨테이너 자원 표본

로그인 토큰은 결과 폴더에 복사하지 않는다.
`.local/`과 `results/`는 Git에서 제외한다.
SQL 통계는 누적값이므로 전후 차이로 해석한다.

## 현재 측정 결과와 한계

본 측정 3회에서 상세 조회는 각각 5,900건이며 응답 검증 실패는 없었다.
평균 응답 시간은 21.21~25.24ms, P95는 42.44~46.00ms였다.
실행 02의 P99는 177.19ms로, 일부 지연의 원인은 확정하지 않았다.

확인한 실행 계획에서는 기존 주문 항목의 `order_id` 인덱스를 사용해
상품 1종·3종 주문의 항목을 각각 1행·3행 반환했다.
현재 조건에서 추가 SQL 최적화의 뚜렷한 근거를 확인하지 못해
애플리케이션 코드와 인덱스는 추가 변경하지 않았다.

앱·DB·부하 발생기가 같은 Mac을 공유하고 제한된 주문을 반복 조회한다.
최대 처리량·2,000명 수용 여부·배포 서버 성능을 증명하는 결과는 아니다.
주문 목록 실험에서 추가한 복합 인덱스가 유지된 상태에서 측정했다.

실행 도구의 앱 코드 기준 `49b39a1`은 기존 실행 앱에 대한 수동 기록이다.
앱을 교체하거나 다시 빌드해 실행하면 해당 기록도 갱신해야 한다.