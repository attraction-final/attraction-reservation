"""재발급과 로그아웃의 경합 및 로그아웃 완료 후 상태 검사."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
from threading import Barrier
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "refresh_checks", ROOT / "check-refresh.py"
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def main():
    parser = argparse.ArgumentParser(
        description="회원별로 재발급과 로그아웃을 함께 시작합니다."
    )
    parser.add_argument("run_name")
    parser.add_argument("--trials", type=int, default=30)
    args = parser.parse_args()

    checker.ensure(
        re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", args.run_name),
        "결과 이름이 올바르지 않습니다.",
    )
    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    checker.ensure(
        1 <= args.trials <= min(100, len(users)),
        "검사 횟수는 준비된 회원 수 이내의 1~100회여야 합니다.",
    )
    selected = users[:args.trials]
    checker.ensure(
        len({user["id"] for user in selected}) == args.trials,
        "검사 회원이 중복됐습니다.",
    )
    checker.ensure(
        checker.helper.sql("SELECT DATABASE();")
        == "attraction_auth_refresh_capacity_perf",
        "재발급 실험 전용 DB가 아닙니다.",
    )

    folder = ROOT / "results" / args.run_name
    folder.mkdir(parents=True, exist_ok=False)
    (folder / path_name()).write_text(
        Path(__file__).read_text(encoding="utf-8"),
        encoding="utf-8",
    )

    report = {
        "passed": False,
        "requestedTrials": args.trials,
        "redisDatabase": 3,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "trials": [],
    }

    try:
        for number, user in enumerate(selected, 1):
            csrf, refresh_token = checker.login(user)
            headers = checker.with_refresh(csrf, refresh_token)
            before = checker.token_state(user["id"])
            checker.ensure(
                before[0] == hashlib.sha256(
                    refresh_token.encode("utf-8")
                ).hexdigest()
                and before[1] > time.time() * 1000 + 120000,
                "검사 준비 상태 확인에 실패했습니다.",
            )

            status, body, _ = checker.request(
                "POST", "/api/auth/refresh", headers=headers
            )
            checker.ensure(status == 200, "검사 준비용 재발급에 실패했습니다.")
            access_token = checker.access_from(body)
            checker.ensure(
                checker.token_state(user["id"]) == before,
                "검사 준비 중 Redis 상태가 변경됐습니다.",
            )

            barrier = Barrier(3, timeout=30)

            def send(endpoint, request_headers):
                barrier.wait()
                started = time.monotonic_ns()
                response = checker.request(
                    "POST", endpoint, headers=request_headers
                )
                ended = time.monotonic_ns()
                return response, started, ended

            with ThreadPoolExecutor(max_workers=2) as pool:
                refresh_future = pool.submit(
                    send, "/api/auth/refresh", headers
                )
                logout_future = pool.submit(
                    send,
                    "/api/auth/logout",
                    {
                        **headers,
                        "Authorization": f"Bearer {access_token}",
                    },
                )
                barrier.wait()
                refresh_result, refresh_start, refresh_end = (
                    refresh_future.result()
                )
                logout_result, logout_start, logout_end = (
                    logout_future.result()
                )

            refresh_status, refresh_body, refresh_headers = refresh_result
            logout_status, logout_body, _ = logout_result

            checker.ensure(
                logout_status == 200
                and logout_body.get("success") is True,
                "경합 중 로그아웃이 성공하지 않았습니다.",
            )
            checker.ensure(
                refresh_status in (200, 401),
                "경합 중 재발급이 예상 범위 밖의 응답을 반환했습니다.",
            )
            if refresh_status == 200:
                checker.access_from(refresh_body)
                checker.ensure(
                    "refreshToken"
                    not in checker.cookies_from(refresh_headers),
                    "경합 중 갱신 토큰 쿠키가 새로 발급됐습니다.",
                )
            else:
                checker.ensure(
                    refresh_body.get("success") is False
                    and not (refresh_body.get("data") or {}).get(
                        "accessToken"
                    ),
                    "경합 중 거절 응답 형식이 다릅니다.",
                )

            checker.ensure(
                checker.token_state(user["id"]) == ("", -2),
                "경합 종료 후 Redis 토큰 키가 남아 있습니다.",
            )

            # 두 요청이 모두 끝난 뒤 새로운 요청으로 검사한다.
            for _ in range(3):
                status, body, _ = checker.request(
                    "POST", "/api/auth/refresh", headers=headers
                )
                checker.ensure(
                    status == 401
                    and body.get("success") is False
                    and not (body.get("data") or {}).get("accessToken"),
                    "로그아웃 완료 후 재발급이 거절되지 않았습니다.",
                )
            checker.ensure(
                checker.token_state(user["id"]) == ("", -2),
                "후속 요청 이후 Redis 토큰 키가 다시 생겼습니다.",
            )

            report["trials"].append({
                "trial": number,
                "userId": user["id"],
                "refreshStatus": refresh_status,
                "logoutStatus": logout_status,
                "clientRequestsOverlapped": (
                    max(refresh_start, logout_start)
                    < min(refresh_end, logout_end)
                ),
                "postLogoutRejections": 3,
                "redisKeyAbsent": True,
            })
            if number % 10 == 0 or number == args.trials:
                print(
                    f"경합 검사: {number}/{args.trials}회 통과",
                    flush=True,
                )

        report["passed"] = True
    except Exception as error:
        report["errorType"] = type(error).__name__
        raise
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (folder / "verification.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    results = report["trials"]
    print(f"전체 검사 통과: {len(results)}회")
    print(
        "경합 중 재발급: "
        f"200 응답 {sum(r['refreshStatus'] == 200 for r in results)}회 / "
        f"401 응답 {sum(r['refreshStatus'] == 401 for r in results)}회"
    )
    print(
        "부하 도구 기준 요청 구간 겹침: "
        f"{sum(r['clientRequestsOverlapped'] for r in results)}회"
    )
    print(f"로그아웃 완료 후 재발급 거절: {len(results) * 3}건")
    print("모든 검사 회원의 Redis 토큰 키 삭제 유지 확인")
    print("서버 내부의 모든 실행 순서를 검증한 결과는 아닙니다.")
    print(f"결과 폴더: {folder}")
    print("토큰·쿠키·비밀번호 원문은 출력하거나 저장하지 않았습니다.")


def path_name():
    return "check-refresh-logout.py"


if __name__ == "__main__":
    main()
