"""주문 집중 요청 전에 전용 회원의 접근 토큰을 준비한다."""
import argparse
import base64
import importlib.util
import json
import os
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(
        description="주문 집중 요청 실험용 접근 토큰을 준비합니다."
    )
    parser.add_argument("--users", type=int, required=True)
    args = parser.parse_args()

    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    if not 1 <= args.users <= min(len(users), 2000):
        parser.error("준비한 회원 수 이내에서 1~2000명을 지정하세요.")

    spec = importlib.util.spec_from_file_location(
        "fixture_helper", ROOT / "prepare-users.py"
    )
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)

    if (
        helper.sql("SELECT DATABASE();")
        != "attraction_order_create_burst_perf"
    ):
        raise RuntimeError("주문 집중 요청 전용 DB가 아닙니다.")

    accounts = []
    for index, user in enumerate(users[:args.users], start=1):
        started_at = time.time()
        status, body, _ = helper.request(
            "POST", "/api/auth/login",
            {"email": user["email"], "password": helper.PASSWORD},
            helper.csrf_headers(),
        )
        data = body.get("data") or {}
        token = data.get("accessToken")
        lifetime = data.get("expiresIn")
        if (
            status != 200
            or body.get("success") is not True
            or data.get("tokenType") != "Bearer"
            or not isinstance(token, str)
            or not token
            or type(lifetime) is not int
            or lifetime <= 0
        ):
            raise RuntimeError(
                f"회원 ID {user['id']}: 로그인 응답 검증 실패"
            )

        payload = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(
            payload + "=" * (-len(payload) % 4)
        ))
        if (
            int(claims["sub"]) != user["id"]
            or claims.get("tokenType") != "ACCESS"
        ):
            raise RuntimeError("토큰의 회원 ID 또는 유형이 다릅니다.")

        accounts.append({
            "expected": {**user, "provider": "LOCAL"},
            "token": token,
            "expiresAt": int((started_at + lifetime) * 1000),
        })
        if index % 100 == 0:
            print(f"토큰 준비: {index}/{args.users}명", flush=True)

    remaining = min(
        account["expiresAt"] for account in accounts
    ) - int(time.time() * 1000)
    if remaining < 120000:
        raise RuntimeError(
            "첫 토큰의 유효 시간이 2분 미만이므로 저장하지 않았습니다."
        )

    target = ROOT / ".local/accounts.json"
    fd = os.open(
        target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600
    )
    with os.fdopen(fd, "w", encoding="utf-8") as file:
        os.fchmod(file.fileno(), 0o600)
        json.dump(accounts, file, ensure_ascii=False)
        file.write("\n")

    print(f"접근 토큰 준비 완료: {len(accounts)}명")
    print(f"가장 먼저 만료되는 토큰의 남은 시간: 약 {remaining // 1000}초")
    print("저장 위치: .local/accounts.json / 파일 권한: 600")
    print("토큰·쿠키·비밀번호 원문은 출력하지 않았습니다.")


if __name__ == "__main__":
    main()
