"""개인 환경의 토큰 재발급 정확성 검사."""
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

# 기존 회원 준비 코드의 DB 설정과 CSRF 준비 함수를 재사용한다.
# 모듈로 읽으므로 회원 준비 main()은 실행하지 않는다.
spec = importlib.util.spec_from_file_location(
    "refresh_fixture", ROOT / "prepare-users.py"
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

    # 401·403도 검사 대상이므로 응답으로 받아 비교한다.
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

    ensure(isinstance(body, dict), "응답 본문 형식이 다릅니다.")
    return status, body, response_headers


def cookies_from(headers):
    cookies = SimpleCookie()
    for value in headers.get_all("Set-Cookie", []):
        cookies.load(value)
    return cookies


def redis(command, user_id):
    # 토큰 원문을 명령 인자나 화면에 전달하지 않는다.
    result = subprocess.run(
        [
            "docker", "compose", "-f", str(helper.COMPOSE),
            "exec", "-T", "redis",
            "redis-cli", "-n", "1", "--raw",
            command, f"auth:refresh:{user_id}",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    ensure(result.returncode == 0, "Redis 조회 실패")
    return result.stdout.strip()


def token_state(user_id):
    token_hash = redis("GET", user_id)
    raw_expiration = redis("PEXPIRETIME", user_id)
    try:
        expiration = int(raw_expiration)
    except ValueError:
        raise RuntimeError(
            "Redis 절대 만료 시각 조회 실패. PEXPIRETIME 지원을 확인하세요."
        ) from None

    return token_hash, expiration


def with_refresh(csrf, refresh_token):
    return {
        **csrf,
        "Cookie": csrf["Cookie"] + f"; refreshToken={refresh_token}",
    }


def access_from(body):
    data = body.get("data") or {}
    access_token = data.get("accessToken")

    ensure(
        body.get("success") is True
        and isinstance(access_token, str)
        and bool(access_token)
        and data.get("tokenType") == "Bearer"
        and data.get("expiresIn") == 600,
        "접근 토큰 응답 검증 실패",
    )
    ensure(
        "refreshToken" not in data,
        "응답 본문에 갱신 토큰이 포함됐습니다.",
    )
    return access_token


def login(user):
    csrf = helper.csrf_headers()
    status, body, headers = request(
        "POST",
        "/api/auth/login",
        {
            "email": user["email"],
            "password": helper.PASSWORD,
        },
        csrf,
    )
    ensure(status == 200, "검사 준비 로그인 실패")
    access_from(body)

    cookies = cookies_from(headers)
    ensure("refreshToken" in cookies, "갱신 토큰 쿠키 없음")
    return csrf, cookies["refreshToken"].value


def expect_rejection(label, expected_status, headers):
    status, body, _ = request(
        "POST", "/api/auth/refresh", headers=headers
    )
    ensure(
        status == expected_status and body.get("success") is False,
        f"{label}: 예상한 거절 응답이 아닙니다.",
    )
    data = body.get("data") or {}
    ensure(
        not data.get("accessToken"),
        f"{label}: 거절 응답에 접근 토큰이 있습니다.",
    )
    print(f"{label}: {expected_status} 거절 확인")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_name")
    args = parser.parse_args()

    ensure(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", args.run_name),
        "결과 폴더 이름이 올바르지 않습니다.",
    )
    ensure(
        helper.sql("SELECT DATABASE();") == "attraction_auth_perf",
        "인증 실험 전용 DB가 아닙니다.",
    )

    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    ensure(
        len(users) == 10
        and len({u["id"] for u in users}) == 10,
        "서로 다른 회원 10명의 검증 기준이 필요합니다.",
    )

    folder = ROOT / "results" / args.run_name
    folder.mkdir(parents=True, exist_ok=False)

    first_session = None

    for user in users:
        csrf, refresh_token = login(user)
        before = token_state(user["id"])

        expected_hash = hashlib.sha256(
            refresh_token.encode("utf-8")
        ).hexdigest()
        ensure(
            before[0] == expected_hash and before[1] > 0,
            "로그인 후 Redis 해시 또는 만료 시각 검증 실패",
        )

        for _ in range(2):
            status, body, headers = request(
                "POST",
                "/api/auth/refresh",
                headers=with_refresh(csrf, refresh_token),
            )
            ensure(status == 200, "정상 재발급 HTTP 상태 불일치")
            access_token = access_from(body)

            ensure(
                "refreshToken" not in cookies_from(headers),
                "현재 정책과 달리 갱신 토큰 쿠키가 재발급됐습니다.",
            )
            ensure(
                token_state(user["id"]) == before,
                "재발급 후 Redis 해시 또는 절대 만료 시각이 변경됐습니다.",
            )

            status, body, _ = request(
                "GET",
                "/api/users/me",
                headers={"Authorization": f"Bearer {access_token}"},
            )
            actual = body.get("data") or {}
            ensure(
                status == 200
                and body.get("success") is True
                and all(
                    actual.get(key) == value
                    for key, value in user.items()
                ),
                "재발급 토큰의 본인 정보 조회 검증 실패",
            )

        if first_session is None:
            first_session = (
                user, csrf, refresh_token, access_token, before
            )

    print("정상 재발급: 회원 10명 × 2회, 총 20건 통과")
    print("재발급 접근 토큰으로 본인 정보 조회: 20건 통과")
    print("Redis 갱신 토큰 해시·절대 만료 시각 유지 확인")

    user, csrf, refresh_token, access_token, before = first_session

    expect_rejection("갱신 토큰 쿠키 누락", 401, csrf)
    expect_rejection(
        "잘못된 형식의 갱신 토큰",
        401,
        with_refresh(csrf, "invalid-local-test-token"),
    )
    expect_rejection(
        "접근 토큰을 갱신 토큰으로 사용",
        401,
        with_refresh(csrf, access_token),
    )
    expect_rejection(
        "CSRF 누락",
        403,
        {"Cookie": f"refreshToken={refresh_token}"},
    )

    ensure(
        token_state(user["id"]) == before,
        "거절 요청 이후 기존 갱신 토큰 저장 상태가 변경됐습니다.",
    )

    status, body, _ = request(
        "POST",
        "/api/auth/logout",
        headers={
            **with_refresh(csrf, refresh_token),
            "Authorization": f"Bearer {access_token}",
        },
    )
    ensure(
        status == 200 and body.get("success") is True,
        "무효화 검사 준비용 로그아웃 실패",
    )
    ensure(
        token_state(user["id"]) == ("", -2),
        "로그아웃 후 Redis 갱신 토큰 키가 남아 있습니다.",
    )
    expect_rejection(
        "로그아웃으로 무효화된 갱신 토큰",
        401,
        with_refresh(csrf, refresh_token),
    )

    report = {
        "passed": True,
        "userCount": 10,
        "refreshSuccessCount": 20,
        "identityCheckCount": 20,
        "redisDatabase": 1,
        "refreshHashPreserved": True,
        "absoluteExpirationPreserved": True,
        "missingCookieStatus": 401,
        "malformedTokenStatus": 401,
        "accessTokenAsRefreshStatus": 401,
        "missingCsrfStatus": 403,
        "revokedTokenStatus": 401,
        "loggedOutTestUserCount": 1,
    }
    (folder / "verification.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print("전체 재발급 검사 통과")
    print("전용 회원 1명은 로그아웃 상태이며 다음 부하 준비에서 다시 로그인합니다.")
    print(f"결과 폴더: {folder}")
    print("토큰·쿠키·비밀번호 원문은 출력하거나 저장하지 않았습니다.")


if __name__ == "__main__":
    main()