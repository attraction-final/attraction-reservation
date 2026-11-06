"""로컬 로그인 정확성 검사. 인증 원문은 출력·저장하지 않는다."""
import argparse
import hashlib
import importlib.util
import json
from http.cookies import SimpleCookie
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent

spec = importlib.util.spec_from_file_location(
    "auth_fixture", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def ensure(condition, message):
    if not condition:
        raise RuntimeError(message)


def request(method, path, payload=None, headers=None):
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")

    req = urllib.request.Request(
        helper.BASE + path,
        data=data,
        headers={
            "Content-Type": "application/json",
            **(headers or {}),
        },
        method=method,
    )
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error

    with response:
        status = response.code
        response_headers = response.headers
        raw = response.read()

    try:
        body = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise RuntimeError(
            f"{method} {path}: JSON 응답이 아닙니다. HTTP {status}"
        ) from None

    return status, body, response_headers


def cookies_from(headers):
    cookies = SimpleCookie()
    for value in headers.get_all("Set-Cookie", []):
        cookies.load(value)
    return cookies


def redis_hash(user_id):
    result = subprocess.run(
        [
            "docker", "compose", "-f", str(helper.COMPOSE),
            "exec", "-T", "redis",
            "redis-cli", "-n", "1", "--raw",
            "GET", f"auth:refresh:{user_id}",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    ensure(result.returncode == 0, "Redis 조회 실패")
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_name")
    args = parser.parse_args()

    ensure(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", args.run_name),
        "결과 폴더 이름이 올바르지 않습니다.",
    )
    ensure(
        helper.sql("SELECT DATABASE();") == helper.DATABASE,
        "로그인 전용 DB가 아닙니다.",
    )

    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    ensure(len(users) == 10, "검증 기준 회원은 10명이어야 합니다.")

    folder = ROOT / "results" / args.run_name
    folder.mkdir(parents=True, exist_ok=False)

    for expected in users:
        status, body, headers = request(
            "POST",
            "/api/auth/login",
            {
                "email": expected["email"],
                "password": helper.PASSWORD,
            },
            helper.csrf_headers(),
        )
        ensure(
            status == 200 and body.get("success") is True,
            "정상 로그인 응답 검증 실패",
        )

        token_data = body.get("data", {})
        access_token = token_data.get("accessToken")
        ensure(
            isinstance(access_token, str) and bool(access_token),
            "Access Token 발급 확인 실패",
        )
        ensure(
            token_data.get("tokenType") == "Bearer"
            and token_data.get("expiresIn") == 600,
            "Access Token 응답 정보 불일치",
        )
        ensure(
            "refreshToken" not in token_data,
            "응답 본문에 Refresh Token이 포함됐습니다.",
        )

        cookies = cookies_from(headers)
        ensure("refreshToken" in cookies, "Refresh Token 쿠키 없음")
        refresh_cookie = cookies["refreshToken"]
        ensure(
            bool(refresh_cookie["httponly"])
            and bool(refresh_cookie["secure"])
            and refresh_cookie["samesite"].lower() == "lax"
            and refresh_cookie["path"] == "/api/auth",
            "Refresh Token 쿠키 속성 불일치",
        )

        expected_hash = hashlib.sha256(
            refresh_cookie.value.encode("utf-8")
        ).hexdigest()
        ensure(
            redis_hash(expected["id"]) == expected_hash,
            "Redis 1번 공간의 토큰 해시 불일치",
        )

        status, body, _ = request(
            "GET",
            "/api/users/me",
            headers={"Authorization": f"Bearer {access_token}"},
        )
        ensure(
            status == 200 and body.get("success") is True,
            "발급 토큰을 이용한 회원 조회 실패",
        )
        actual = body.get("data", {})
        ensure(
            all(actual.get(key) == value for key, value in expected.items()),
            "로그인 회원과 조회된 회원 정보가 다릅니다.",
        )

    print("정상 로그인 및 본인 정보 조회: 10명 모두 통과")
    print("Refresh Token 쿠키 속성 및 Redis 1번 해시 저장 확인")

    status, body, headers = request(
        "POST",
        "/api/auth/login",
        {
            "email": users[0]["email"],
            "password": "WrongLocal123!",
        },
        helper.csrf_headers(),
    )
    ensure(
        status == 401 and body.get("success") is False,
        "잘못된 비밀번호 거절 검증 실패",
    )
    ensure(
        "refreshToken" not in cookies_from(headers),
        "로그인 실패 응답에 Refresh Token 쿠키가 있습니다.",
    )
    print("잘못된 비밀번호: 401 거절 확인")

    status, body, _ = request(
        "POST",
        "/api/auth/login",
        {
            "email": users[0]["email"],
            "password": helper.PASSWORD,
        },
    )
    ensure(
        status == 403 and body.get("success") is False,
        "CSRF 누락 거절 검증 실패",
    )
    print("CSRF 누락 로그인: 403 거절 확인")

    status, body, _ = request("GET", "/api/users/me")
    ensure(
        status == 401 and body.get("success") is False,
        "미인증 회원 조회 거절 검증 실패",
    )
    print("미인증 내 정보 조회: 401 거절 확인")

    report = {
        "passed": True,
        "userCount": len(users),
        "normalLoginAndIdentity": True,
        "refreshCookieAttributes": True,
        "redisDatabase": 1,
        "refreshTokenHashMatched": True,
        "wrongPasswordStatus": 401,
        "missingCsrfStatus": 403,
        "unauthenticatedMeStatus": 401,
    }
    (folder / "verification.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"전체 검사 통과. 결과 폴더: {folder}")
    print("토큰·쿠키·비밀번호 원문은 출력하거나 저장하지 않았습니다.")


if __name__ == "__main__":
    main()