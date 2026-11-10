"""재발급 부하 측정 전에 회원별 세션과 Redis 기준을 준비한다."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "refresh_check", ROOT / "check-refresh.py"
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(
        description="부하 측정 전에 로그인 세션과 Redis 기준을 준비합니다."
    )
    parser.add_argument("--users", type=int, required=True)
    args = parser.parse_args()

    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    require(
        1 <= args.users <= min(len(users), 2000),
        "준비한 회원 수 이내에서 1~2000명을 지정하세요.",
    )
    require(
        checker.helper.sql("SELECT DATABASE();")
        == "attraction_auth_refresh_capacity_perf",
        "재발급 실험 전용 DB가 아닙니다.",
    )

    selected = users[:args.users]
    ids = [user["id"] for user in selected]
    require(
        all(type(user_id) is int and user_id > 0 for user_id in ids)
        and len(set(ids)) == len(ids),
        "회원 ID가 올바르지 않거나 중복됐습니다.",
    )

    accounts = []
    for index, user in enumerate(selected, start=1):
        csrf, refresh_token = checker.login(user)
        token_hash, expiration = checker.token_state(user["id"])
        expected_hash = hashlib.sha256(
            refresh_token.encode("utf-8")
        ).hexdigest()

        require(
            token_hash == expected_hash
            and expiration
            > datetime.now(timezone.utc).timestamp() * 1000 + 120000,
            "Redis 토큰 해시 또는 남은 유효 시간 확인 실패",
        )

        accounts.append({
            "userId": user["id"],
            "refreshHeaders": checker.with_refresh(csrf, refresh_token),
            "tokenHash": token_hash,
            "absoluteExpiration": expiration,
        })

        if index % 25 == 0:
            print(
                f"로그인 준비: {index}/{args.users}명",
                flush=True,
            )

    data = {
        "preparedAt": datetime.now(timezone.utc).isoformat(),
        "redisDatabase": 3,
        "userCount": len(accounts),
        "accounts": accounts,
    }

    target = ROOT / ".local/load-accounts.json"
    descriptor = os.open(
        target,
        os.O_WRONLY | os.O_CREAT | os.O_TRUNC,
        0o600,
    )
    with os.fdopen(descriptor, "w", encoding="utf-8") as file:
        os.fchmod(file.fileno(), 0o600)
        json.dump(data, file, ensure_ascii=False)
        file.write("\n")

    print(f"재발급 부하 준비 완료: 서로 다른 회원 {len(accounts)}명")
    print("회원별 Redis 토큰 해시·절대 만료 시각 확인")
    print("저장 위치: .local/load-accounts.json / 파일 권한: 600")
    print("아직 재발급 부하 요청은 보내지 않았습니다.")
    print("토큰·쿠키·비밀번호 원문은 출력하지 않았습니다.")


if __name__ == "__main__":
    main()
