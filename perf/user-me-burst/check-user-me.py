"""전용 회원의 로그인과 본인 정보 조회를 검사한다."""
import argparse
import importlib.util
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_name")
    args = parser.parse_args()

    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", args.run_name):
        parser.error("결과 이름은 영문 소문자·숫자·하이픈으로 지정하세요.")

    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    if not users:
        raise RuntimeError("준비한 회원이 없습니다.")

    spec = importlib.util.spec_from_file_location(
        "fixture_helper", ROOT / "prepare-users.py"
    )
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)

    if helper.sql("SELECT DATABASE();") != helper.DATABASE:
        raise RuntimeError("실험 DB 확인 실패")

    result_dir = ROOT / "results" / args.run_name
    result_dir.mkdir(parents=True, exist_ok=False)

    verified = 0
    for user in users:
        status, body, _ = helper.request(
            "POST",
            "/api/auth/login",
            {
                "email": user["email"],
                "password": helper.PASSWORD,
            },
            helper.csrf_headers(),
        )
        data = body.get("data") or {}
        token = data.get("accessToken")
        if (
            status != 200
            or body.get("success") is not True
            or not isinstance(token, str)
            or not token
            or data.get("tokenType") != "Bearer"
        ):
            raise RuntimeError(
                f"회원 ID {user['id']}: 로그인 응답 검증 실패"
            )

        status, body, _ = helper.request(
            "GET",
            "/api/users/me",
            headers={"Authorization": f"Bearer {token}"},
        )
        if (
            status != 200
            or body.get("success") is not True
            or body.get("data") != {**user, "provider": "LOCAL"}
        ):
            raise RuntimeError(
                f"회원 ID {user['id']}: 본인 정보 검증 실패"
            )
        verified += 1

    report = {
        "result": "PASS",
        "verified_users": verified,
        "login_successes": verified,
        "user_me_successes": verified,
        "identity_matches": verified,
    }
    (result_dir / "verification.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"로그인: {verified}명 모두 통과")
    print(f"내 정보 조회: {verified}명 모두 통과")
    print("회원 ID·이메일·닉네임·권한·상태·가입 방식 및 응답 필드 일치")
    print(f"결과 폴더: {result_dir}")
    print("토큰·쿠키·비밀번호 원문은 출력하거나 저장하지 않았습니다.")


if __name__ == "__main__":
    main()
