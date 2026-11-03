"""개인 테스트 회원의 내 정보 조회 검증 기준을 저장한다."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent

sys.path.insert(0, str(ROOT.parent / "order-list"))
from prepare import sql


def main():
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
        WHERE email REGEXP '^perf[0-9]{4}@example[.]test$'
        ORDER BY id;
    """)

    users = [json.loads(line) for line in rows.splitlines()]

    if len(users) != 100:
        raise RuntimeError("테스트 회원이 100명이 아닙니다.")

    if len({user["id"] for user in users}) != 100:
        raise RuntimeError("회원 ID가 중복됩니다.")

    for user in users:
        if user["status"] != "ACTIVE":
            raise RuntimeError("활성 상태가 아닌 테스트 회원이 있습니다.")

        if not user["nickname"] or not user["role"]:
            raise RuntimeError("회원 검증에 필요한 정보가 누락됐습니다.")

    local = ROOT / ".local"
    local.mkdir(parents=True, exist_ok=True)

    target = local / "user-fixture.json"
    target.write_text(
        json.dumps(users, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"검증 기준 준비 완료: 테스트 회원 {len(users)}명")
    print("검증 항목: ID·이메일·닉네임·권한·상태")
    print("비밀번호와 토큰은 조회하거나 저장하지 않았습니다.")


if __name__ == "__main__":
    main()