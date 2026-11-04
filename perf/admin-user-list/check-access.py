"""개인 테스트 환경의 관리자 회원 목록 접근 권한 확인."""
import json
from pathlib import Path
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "order-list"))

from prepare import BASE, PASSWORD, csrf, request, sql


def fetch_list(token=None):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(
        BASE + "/api/admin/users?page=0&size=10",
        headers=headers,
    )

    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error

    with response:
        return response.status, json.load(response)


def login(email):
    body, _ = request(
        "POST",
        "/api/auth/login",
        {"email": email, "password": PASSWORD},
        csrf(),
    )
    return body["data"]["accessToken"]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    require(
        sql("SELECT DATABASE();") == "attraction_perf",
        "개인 테스트 DB가 아닙니다.",
    )

    status, _ = fetch_list()
    require(status == 401, f"미인증 요청: 예상 401, 실제 {status}")
    print("미인증 요청: 401 확인")

    user_token = login("perf0000@example.test")
    status, _ = fetch_list(user_token)
    require(status == 403, f"일반 회원 요청: 예상 403, 실제 {status}")
    print("일반 회원 요청: 403 확인")

    admin_token = login("perfadmin0000@example.test")
    status, body = fetch_list(admin_token)
    require(status == 200, f"관리자 요청: 예상 200, 실제 {status}")
    require(body.get("success") is True, "업무 성공 응답이 아닙니다.")

    total = int(sql("""
        SELECT COUNT(*)
        FROM users
        WHERE status <> 'WITHDRAWN';
    """))
    expected_ids = [
        int(value)
        for value in sql("""
            SELECT id
            FROM users
            WHERE status <> 'WITHDRAWN'
            ORDER BY created_at DESC, id DESC
            LIMIT 10;
        """).splitlines()
    ]

    data = body["data"]
    require(data["page"] == 0, "페이지 번호가 다릅니다.")
    require(data["size"] == 10, "페이지 크기가 다릅니다.")
    require(data["totalElements"] == total, "전체 회원 수가 다릅니다.")
    require(
        data["totalPages"] == (total + 9) // 10,
        "전체 페이지 수가 다릅니다.",
    )
    require(
        [item["id"] for item in data["content"]] == expected_ids,
        "회원 ID 또는 정렬 순서가 다릅니다.",
    )
    require(
        all(item["status"] != "WITHDRAWN" for item in data["content"]),
        "목록에 탈퇴 회원이 포함됐습니다.",
    )

    print("관리자 요청: 200 및 업무 성공 확인")
    print("페이지 정보·전체 건수·회원 ID·정렬 순서 확인")
    print(f"조회 대상: {total}명")
    print("토큰은 출력하거나 파일에 저장하지 않았습니다.")


if __name__ == "__main__":
    main()