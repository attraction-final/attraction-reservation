"""개인 로그인 실험용 회원 준비. 토큰과 비밀번호를 출력하지 않는다."""
import json
from http.cookies import SimpleCookie
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
COMPOSE = ROOT.parent / "order-list" / "compose.yaml"
BASE = "http://127.0.0.1:18090"
DATABASE = "attraction_auth_perf"

# 개인 실험 전용 공개 비밀번호. 실제 계정에는 사용하지 않는다.
PASSWORD = "PerfLocal123!"
USER_COUNT = 10


def sql(query):
    result = subprocess.run(
        [
            "docker", "compose", "-f", str(COMPOSE),
            "exec", "-T", "mysql",
            "mysql", "--connect-timeout=5",
            "--default-character-set=utf8mb4",
            "-uperf", "-pperf-local-only",
            "--raw", "-N", "-B", DATABASE,
            "-e", query,
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if result.returncode != 0:
        raise RuntimeError("전용 DB 조회 실패. DB 연결을 확인하세요.")
    return result.stdout.strip()

def request(method, path, payload=None, headers=None):
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")

    req = urllib.request.Request(
        BASE + path,
        data=data,
        headers={
            "Content-Type": "application/json",
            **(headers or {}),
        },
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.load(response), response.headers
    except urllib.error.HTTPError as error:
        raise RuntimeError(
            f"{method} {path}: HTTP {error.code}"
        ) from None


def csrf_headers():
    status, body, headers = request("GET", "/api/auth/csrf")
    if status != 200 or body.get("success") is not True:
        raise RuntimeError("CSRF 준비 실패")

    cookies = SimpleCookie()
    for value in headers.get_all("Set-Cookie", []):
        cookies.load(value)

    if not cookies:
        raise RuntimeError("CSRF 쿠키가 발급되지 않았습니다.")

    # 로컬 HTTP 검사에서 Secure 쿠키를 명시적으로 전달한다.
    # 서버의 쿠키 보안 설정은 변경하지 않는다.
    return {
        body["data"]["headerName"]: body["data"]["token"],
        "Cookie": "; ".join(
            f"{name}={cookie.value}"
            for name, cookie in cookies.items()
        ),
    }


def main():
    if sql("SELECT DATABASE();") != DATABASE:
        raise RuntimeError("로그인 전용 DB가 아닙니다.")

    fixture = []
    created = 0
    reused = 0

    for number in range(USER_COUNT):
        email = f"perfauth{number:04d}@example.test"
        nickname = f"로그인실험{number:04d}"

        existing = sql(
            "SELECT id, nickname, role, status, provider "
            f"FROM users WHERE email = '{email}';"
        )

        if not existing:
            status, body, _ = request(
                "POST",
                "/api/users/signup",
                {
                    "email": email,
                    "password": PASSWORD,
                    "nickname": nickname,
                },
                csrf_headers(),
            )
            if status != 201 or body.get("success") is not True:
                raise RuntimeError("회원가입 응답 검증 실패")
            created += 1
        else:
            reused += 1

        row = sql(
            "SELECT id, nickname, role, status, provider "
            f"FROM users WHERE email = '{email}';"
        ).split("\t")

        if len(row) != 5:
            raise RuntimeError("회원 저장 결과 확인 실패")

        user_id, actual_nickname, role, state, provider = row
        if (
            actual_nickname != nickname
            or role != "USER"
            or state != "ACTIVE"
            or provider != "LOCAL"
        ):
            raise RuntimeError("기존 회원이 예상한 실험 조건과 다릅니다.")

        fixture.append({
            "id": int(user_id),
            "email": email,
            "nickname": nickname,
            "role": role,
            "status": state,
        })

    local = ROOT / ".local"
    local.mkdir(parents=True, exist_ok=True)
    (local / "users.json").write_text(
        json.dumps(fixture, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    print(f"로그인 전용 회원 준비 완료: {len(fixture)}명")
    print(f"신규 생성: {created}명 / 기존 회원 확인: {reused}명")
    print("회원 상태: ACTIVE / 권한: USER / 가입 방식: LOCAL")
    print("검증 기준은 Git 제외 폴더 .local/users.json에 저장했습니다.")
    print("비밀번호·토큰은 출력하거나 검증 기준 파일에 저장하지 않았습니다.")


if __name__ == "__main__":
    main()