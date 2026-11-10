"""재발급 측정 결과와 준비 당시 Redis 상태를 대조한다."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "refresh_check", ROOT / "check-refresh.py"
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(
        description="재발급 측정 결과와 Redis 해시·절대 만료 시각을 검증합니다."
    )
    parser.add_argument("run_name")
    parser.add_argument("--vus", type=int, required=True)
    parser.add_argument("--mode", choices=["smoke", "load"], required=True)
    args = parser.parse_args()

    require(
        re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.run_name),
        "실행 이름 형식이 올바르지 않습니다.",
    )
    require(
        checker.helper.sql("SELECT DATABASE();")
        == "attraction_auth_refresh_capacity_perf",
        "재발급 실험 전용 DB가 아닙니다.",
    )

    folder = ROOT / "results" / args.run_name
    target = folder / "verification.json"
    require(not target.exists(), "기존 검증 기록을 덮어쓰지 않습니다.")

    baseline_path = ROOT / ".local/load-accounts.json"
    baseline = read_json(baseline_path)
    require(
        baseline["redisDatabase"] == 3
        and 1 <= args.vus <= len(baseline["accounts"]),
        "준비한 세션 수 또는 Redis 번호가 다릅니다.",
    )
    require(
        baseline_path.stat().st_mtime
        <= (folder / "summary.json").stat().st_mtime,
        "측정 후 세션 준비 파일이 변경됐습니다. 기존 기준 확인이 필요합니다.",
    )

    accounts = baseline["accounts"][:args.vus]
    require(
        len({account["userId"] for account in accounts}) == args.vus,
        "회원 배정이 중복됐습니다.",
    )

    metrics = read_json(folder / "summary.json")["metrics"]

    def count(name):
        return metrics.get(name, {}).get("values", {}).get("count", 0)

    attempts = count("auth_refresh_attempts")
    successes = count("auth_refresh_successes")
    checks = metrics.get("checks", {}).get("values", {})
    completed = re.findall(
        r"(\d+) complete and (\d+) interrupted iterations",
        (folder / "console.log").read_text(encoding="utf-8"),
    )

    metrics_valid = (
        attempts > 0
        and attempts == successes == count("iterations") == count("http_reqs")
        and checks.get("passes") == attempts * 7
        and checks.get("fails") == 0
        and metrics.get("http_req_failed", {}).get("values", {}).get("rate") == 0
        and metrics.get("auth_refresh_failed", {}).get("values", {}).get("rate") == 0
        and metrics.get("vus_max", {}).get("values", {}).get("max") == args.vus
        and bool(completed)
        and completed[-1] == (str(attempts), "0")
        and int((folder / "exit-code.txt").read_text().strip()) == 0
    )
    if args.mode == "smoke":
        metrics_valid = metrics_valid and attempts == args.vus * 5

    changed_users = []
    for account in accounts:
        actual = checker.token_state(account["userId"])
        expected = (
            account["tokenHash"],
            account["absoluteExpiration"],
        )
        if actual != expected:
            changed_users.append(account["userId"])

    redis_valid = not changed_users
    passed = metrics_valid and redis_valid

    report = {
        "runName": args.run_name,
        "mode": args.mode,
        "vus": args.vus,
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "sessionPreparedAt": baseline["preparedAt"],
        "passed": passed,
        "metricsValid": metrics_valid,
        "redisDatabase": 3,
        "redisStatePreserved": redis_valid,
        "checkedUsers": len(accounts),
        "changedUserIds": changed_users,
        "attempts": attempts,
        "successes": successes,
        "checksPassed": checks.get("passes"),
        "checksFailed": checks.get("fails"),
    }
    with target.open("x", encoding="utf-8") as file:
        json.dump(report, file, ensure_ascii=False, indent=2)
        file.write("\n")

    print(f"재발급 검증: {args.run_name}")
    print(f"재발급 시도: {attempts}건 / 성공: {successes}건")
    print(f"응답 검증: {checks.get('passes', 0)}개 통과")
    print(f"측정 결과 검증: {metrics_valid}")
    print(f"Redis 검사 회원 수: {len(accounts)}명")
    print(f"Redis 토큰 해시·절대 만료 시각 유지: {redis_valid}")
    print(f"최종 검증 통과: {passed}")
    print("토큰·쿠키·비밀번호 원문은 출력하거나 저장하지 않았습니다.")

    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
