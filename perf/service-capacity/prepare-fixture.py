"""사용자 배정과 DB 기준을 준비하고 소규모 조회 API를 검사한다."""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "service_helper", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def ensure(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(
        description="통합 부하의 사용자 배정과 조회 기준을 준비합니다."
    )
    parser.add_argument("--users", type=int, required=True)
    parser.add_argument("--check-api", action="store_true")
    args = parser.parse_args()

    users = json.loads(
        (ROOT / ".local/users.json").read_text(encoding="utf-8")
    )
    ensure(
        10 <= args.users <= min(2000, len(users))
        and args.users % 10 == 0,
        "회원 수는 준비된 범위 안의 10명 단위여야 합니다.",
    )
    users = users[:args.users]
    ids = [int(user["id"]) for user in users]
    ensure(
        len(set(ids)) == len(ids) and all(uid > 0 for uid in ids),
        "회원 ID가 중복되거나 올바르지 않습니다.",
    )
    ensure(
        helper.sql("SELECT DATABASE();")
        == "attraction_service_capacity_perf",
        "통합 실험 전용 DB가 아닙니다.",
    )

    products = {}
    for line in helper.sql("""
        SELECT name, id, price, stock, status
        FROM ticket_products
        WHERE name IN ('PERF_SERVICE_HISTORY', 'PERF_SERVICE_WRITE');
    """).splitlines():
        name, pid, price, stock, status = line.split("\t")
        ensure(
            price == "10000" and status == "ON_SALE",
            "전용 상품 상태가 예상과 다릅니다.",
        )
        products[name] = {"id": int(pid), "stock": int(stock)}
    ensure(len(products) == 2, "전용 상품 두 개가 필요합니다.")
    ensure(
        products["PERF_SERVICE_WRITE"]["stock"] == 10000,
        "생성·취소용 상품 재고가 복구되지 않았습니다.",
    )

    orders = {uid: [] for uid in ids}
    rows = helper.sql(f"""
        SELECT user_id, id, total_amount, status
        FROM orders
        WHERE user_id IN ({','.join(map(str, ids))})
        ORDER BY user_id, created_at DESC, id DESC;
    """)
    for line in rows.splitlines():
        uid, oid, amount, status = line.split("\t")
        orders[int(uid)].append({
            "orderId": int(oid),
            "totalAmount": int(amount),
            "status": status,
        })

    accounts = []
    for index, user in enumerate(users):
        uid = user["id"]
        position = index % 10
        group = (
            "order_list" if position < 7
            else "user_me" if position < 9
            else "order_write"
        )
        account = {
            "userId": uid,
            "group": group,
            "expected": {**user, "provider": "LOCAL"},
        }
        if group == "order_list":
            own_orders = orders[uid]
            ensure(
                len(own_orders) == 100
                and all(
                    row["totalAmount"] == 10000
                    and row["status"] == "CANCELLED"
                    for row in own_orders
                ),
                f"회원 {uid}: 조회용 주문 기준이 다릅니다.",
            )
            account["total"] = len(own_orders)
            account["pages"] = {
                str(page): [
                    row["orderId"]
                    for row in own_orders[page * 10:page * 10 + 10]
                ]
                for page in (0, 5, 9)
            }
        accounts.append(account)

    fixture = {
        "userCount": args.users,
        "historyProductId": products["PERF_SERVICE_HISTORY"]["id"],
        "writeProductId": products["PERF_SERVICE_WRITE"]["id"],
        "accounts": accounts,
    }

    checked_me = 0
    checked_pages = 0
    if args.check_api:
        tokens = json.loads(
            (ROOT / ".local/accounts.json").read_text(encoding="utf-8")
        )
        token_map = {
            account["expected"]["id"]: account["token"]
            for account in tokens
        }
        ensure(
            all(uid in token_map for uid in ids),
            "대상 회원의 접근 토큰을 먼저 준비하세요.",
        )

        for account in accounts:
            uid = account["userId"]
            headers = {"Authorization": f"Bearer {token_map[uid]}"}
            status, body, _ = helper.request(
                "GET", "/api/users/me", headers=headers
            )
            ensure(
                status == 200
                and body.get("success") is True
                and body.get("data") == account["expected"],
                f"회원 {uid}: 본인 정보 조회 검증 실패",
            )
            checked_me += 1

            if account["group"] != "order_list":
                continue

            for page, expected_ids in account["pages"].items():
                status, body, _ = helper.request(
                    "GET",
                    f"/api/orders/me?page={page}&size=10",
                    headers=headers,
                )
                data = body.get("data") or {}
                content = data.get("content")
                ensure(
                    status == 200
                    and body.get("success") is True
                    and data.get("page") == int(page)
                    and data.get("size") == 10
                    and data.get("totalElements") == 100
                    and data.get("totalPages") == 10
                    and isinstance(content, list)
                    and [row.get("orderId") for row in content]
                        == expected_ids
                    and all(
                        row.get("totalAmount") == 10000
                        and row.get("status") == "CANCELLED"
                        and row.get("items") == [{
                            "ticketProductId": fixture["historyProductId"],
                            "ticketProductName": "PERF_SERVICE_HISTORY",
                            "quantity": 1,
                        }]
                        for row in content
                    ),
                    f"회원 {uid}: 주문 목록 {page}페이지 검증 실패",
                )
                checked_pages += 1

    (ROOT / ".local/service-fixture.json").write_text(
        json.dumps(fixture, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    for group in ("order_list", "user_me", "order_write"):
        count = sum(a["group"] == group for a in accounts)
        print(f"사용자 배정: {group} / {count}명")
    if args.check_api:
        print(f"본인 정보 조회: {checked_me}건 통과")
        print(f"주문 목록 조회: {checked_pages}건 통과")
    print("비교 기준 저장: .local/service-fixture.json")
    print("주문·재고 변경 없음 / 토큰·비밀번호 저장 없음")


if __name__ == "__main__":
    main()
