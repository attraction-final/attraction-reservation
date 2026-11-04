"""개인 테스트 환경의 관리자 인증·목록 검증 기준 준비."""
import base64
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "order-list"))

from prepare import PASSWORD, csrf, request, sql

PAGE_SIZE = 10
ADMIN_EMAIL = "perfadmin0000@example.test"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def save_json(path, value):
    # 임시 파일을 소유자만 읽고 쓸 수 있게 만든 뒤 교체한다.
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
    require(
        sql("SELECT DATABASE();") == "attraction_perf",
        "개인 테스트 DB가 아닙니다.",
    )

    admin_id = sql("""
        SELECT id
        FROM users
        WHERE email = 'perfadmin0000@example.test'
          AND role = 'ADMIN'
          AND status = 'ACTIVE'
          AND provider = 'LOCAL';
    """)
    require(admin_id.isdigit(), "활성 테스트 관리자 계정이 없습니다.")
    admin_id = int(admin_id)

    total = int(sql("""
        SELECT COUNT(*)
        FROM users
        WHERE status <> 'WITHDRAWN';
    """))
    require(total > 0, "조회 대상 회원이 없습니다.")

    total_pages = (total + PAGE_SIZE - 1) // PAGE_SIZE
    page_numbers = sorted({0, total_pages // 2, total_pages - 1})

    pages = {}

    for page in page_numbers:
        offset = page * PAGE_SIZE

        rows = sql(f"""
            SET NAMES utf8mb4;

            SELECT JSON_OBJECT(
                'id', id,
                'email', email,
                'nickname', nickname,
                'role', role,
                'status', status
            )
            FROM users
            WHERE status <> 'WITHDRAWN'
            ORDER BY created_at DESC, id DESC
            LIMIT {PAGE_SIZE} OFFSET {offset};
        """)

        content = [
            json.loads(line)
            for line in rows.splitlines()
        ]
        expected_count = min(PAGE_SIZE, total - offset)

        require(
            len(content) == expected_count,
            f"페이지 {page}의 검증 기준 건수가 다릅니다.",
        )

        pages[str(page)] = {
            "page": page,
            "content": content,
        }

    body, _ = request(
        "POST",
        "/api/auth/login",
        {"email": ADMIN_EMAIL, "password": PASSWORD},
        csrf(),
    )
    token = body["data"]["accessToken"]

    payload = token.split(".")[1]
    claims = json.loads(
        base64.urlsafe_b64decode(
            payload + "=" * (-len(payload) % 4)
        )
    )
    expires_at = int(claims["exp"]) * 1000

    require(
        int(claims["sub"]) == admin_id
        and claims.get("tokenType") == "ACCESS",
        "관리자 토큰의 회원 또는 유형이 다릅니다.",
    )
    require(
        expires_at > time.time() * 1000 + 120000,
        "토큰 잔여 시간이 부족합니다.",
    )

    local = ROOT / ".local"
    local.mkdir(parents=True, exist_ok=True)

    save_json(local / "fixture.json", {
        "size": PAGE_SIZE,
        "totalElements": total,
        "totalPages": total_pages,
        "pages": pages,
    })

    save_json(local / "accounts.json", [{
        "userId": admin_id,
        "token": token,
        "expiresAt": expires_at,
    }])

    print(f"관리자 인증 준비 완료: 회원 ID {admin_id}")
    print(f"목록 조회 대상: {total}명 / 전체 {total_pages}페이지")

    for page in page_numbers:
        count = len(pages[str(page)]["content"])
        print(f"검증 기준: 페이지 {page} / {count}명")

    print("토큰은 화면에 출력하지 않았습니다.")
    print("토큰과 검증 기준은 Git 제외 폴더 .local에 저장했습니다.")


if __name__ == "__main__":
    main()