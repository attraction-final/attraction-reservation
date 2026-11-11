#!/bin/sh
# MySQL 컨테이너 내부에서 실행한다. host에 MySQL client를 설치할 필요가 없다.
set -eu
case "${RUN_ID:-}" in
  ''|*[!A-Za-z0-9_-]*) echo '유효한 RUN_ID가 필요합니다.' >&2; exit 1 ;;
esac
target="/results/$RUN_ID"
# 기존 결과를 덮어쓰지 않는다. accounts 준비와 SQL 검증이 끝난 다음 기대 결과를 저장한다.
mkdir "$target"
mysql --host=mysql --user=perf --default-character-set=utf8mb4 --batch --raw --skip-column-names \
  reservation_perf < /scripts/prepare.sql
mysql --host=mysql --user=perf --default-character-set=utf8mb4 --batch --raw --skip-column-names \
  reservation_perf < /scripts/expected.sql > "$target/fixture.jsonl"
test "$(wc -l < "$target/fixture.jsonl")" -eq 400
echo "기대 결과 준비 완료: $RUN_ID"
