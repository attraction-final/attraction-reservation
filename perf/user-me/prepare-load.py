"""내 정보 조회의 단계별 부하용 계정·검증 기준 준비."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "order-list"))

from prepare import PASSWORD, csrf, request, sql

# 준비 시간과 실제 측정 시간을 고려해 여유 있는 토큰만 재사용한다.
TOKEN_MARGIN_MS = 5 * 60 * 1000


def write_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.touch(mode=0o600, exist_ok=False)
    try:
        temporary.write_text(
            json.dumps(value, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--users", type=int, choices=[100, 300, 500, 1000, 2000],
        default=300,
    )
    args = parser.parse_args()

    if sql("SELECT DATABASE();") != "attraction_perf":
        raise RuntimeError("개인 테스트 DB가 아닙니다.")

    rows = sql("""
        SET NAMES utf8mb4;

        SELECT JSON_OBJECT(
            'id', id,
            'email', email,
            'nickname', nickname,
            'role', role,
            'status', status
        )
        FROM users
        WHERE email REGEXP
            '^(perf[0-9]{4}|perfme[0-9]{4})@example[.]test$'
          AND provider = 'LOCAL'
        ORDER BY id;
    """)

    pool = [json.loads(line) for line in rows.splitlines()]

    if len(pool) != 2000:
        raise RuntimeError("준비된 테스트 회원이 2,000명이 아닙니다.")

    if len({user["id"] for user in pool}) != 2000:
        raise RuntimeError("회원 ID가 중복됩니다.")

    if any(
        user["status"] != "ACTIVE" or user["role"] != "USER"
        for user in pool
    ):
        raise RuntimeError("테스트 회원의 상태 또는 권한이 예상과 다릅니다.")

    users = pool[:args.users]
    local = ROOT / ".local"
    local.mkdir(parents=True, exist_ok=True)

    account_path = local / "load-accounts.json"
    cached = {}

    if account_path.exists():
        cached = {
            account["userId"]: account
            for account in json.loads(
                account_path.read_text(encoding="utf-8")
            )
        }

    # 같은 CSRF 쿠키·헤더 쌍을 준비용 로그인 요청에 사용한다.
    csrf_headers = csrf()
    fresh_count = 0

    def prepare_account(user):
        previous = cached.get(user["id"])

        if (
            previous is not None
            and previous["expiresAt"] >
                time.time() * 1000 + TOKEN_MARGIN_MS
        ):
            return previous, False

        body, _ = request(
            "POST",
            "/api/auth/login",
            {"email": user["email"], "password": PASSWORD},
            csrf_headers,
        )
        token = body["data"]["accessToken"]
        payload = token.split(".")[1]
        claims = json.loads(
            base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        )

        if (
            int(claims["sub"]) != user["id"]
            or claims.get("tokenType") != "ACCESS"
        ):
            raise RuntimeError("로그인 토큰의 회원 또는 유형이 다릅니다.")

        return {
            "userId": user["id"],
            "token": token,
            "expiresAt": int(claims["exp"]) * 1000,
        }, True

    accounts = []
    print(f"로그인 준비 시작: {len(users)}명", flush=True)

    # 준비용 로그인만 최대 4개씩 실행한다. k6 측정에는 포함하지 않는다.
    with ThreadPoolExecutor(max_workers=4) as executor:
        for index, (account, issued) in enumerate(
            executor.map(prepare_account, users), start=1
        ):
            accounts.append(account)
            fresh_count += int(issued)

            if index % 100 == 0 or index == len(users):
                print(
                    f"로그인 준비: {index}/{len(users)}명",
                    flush=True,
                )

    if any(
        account["expiresAt"] <= time.time() * 1000 + TOKEN_MARGIN_MS
        for account in accounts
    ):
        raise RuntimeError(
            "준비 중 일부 토큰의 잔여 시간이 부족해졌습니다. "
            "같은 명령어로 다시 준비해주세요."
        )

    write_json(local / "load-user-fixture.json", users)
    write_json(account_path, accounts)

    print(
        f"준비 완료: {len(users)}명 / "
        f"새 토큰 {fresh_count}개 / "
        f"기존 토큰 재사용 {len(users) - fresh_count}개"
    )
    print("회원 정보·비밀번호·토큰 원문은 출력하지 않습니다.")
    print("주문 데이터는 변경하지 않았습니다.")


if __name__ == "__main__":
    main()