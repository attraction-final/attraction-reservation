"""주문 생성·취소 부하 측정의 인증과 시작 상태 준비."""
import base64
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "order-list"))

from prepare import PASSWORD, csrf, request, sql


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def save_json(path, value):
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

    rows = sql("""
        SELECT JSON_OBJECT(
            'id', id,
            'price', price,
            'stock', stock,
            'status', status,
            'version', version
        )
        FROM ticket_products
        WHERE name = 'PERF_ORDER_WRITE_LOAD';
    """).splitlines()
    require(len(rows) == 1, "전용 상품이 없거나 중복됐습니다.")
    product = json.loads(rows[0])
    product_id = int(product["id"])

    require(
        product["price"] == 10000
        and product["stock"] == 10000
        and product["status"] == "ON_SALE"
        and product["version"] is not None,
        "전용 상품의 가격·재고·상태·버전을 확인하세요. 임의로 초기화하지 마세요.",
    )

    users = [
        json.loads(line)
        for line in sql("""
            SELECT JSON_OBJECT('id', id, 'email', email)
            FROM users
            WHERE email REGEXP '^perfwrite[0-9]{4}@example[.]test$'
              AND role = 'USER'
              AND status = 'ACTIVE'
              AND provider = 'LOCAL'
            ORDER BY email;
        """).splitlines()
    ]
    require(
        len(users) == 10 and len({u["id"] for u in users}) == 10,
        "서로 다른 활성 테스트 회원 10명이 필요합니다.",
    )

    before = json.loads(sql(f"""
        SELECT JSON_OBJECT(
            'orders', COUNT(DISTINCT o.id),
            'items', COUNT(oi.id),
            'nonCancelledOrders', COUNT(DISTINCT CASE
                WHEN o.status <> 'CANCELLED' THEN o.id
            END),
            'maxOrderId', COALESCE(MAX(o.id), 0)
        )
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        WHERE oi.ticket_product_id = {product_id};
    """))
    require(
        before["nonCancelledOrders"] == 0,
        "전용 상품에 미취소 주문이 남아 있습니다. 이전 실행 결과를 확인하세요.",
    )

    before["payments"] = int(sql(f"""
        SELECT COUNT(*)
        FROM payments p
        WHERE EXISTS (
            SELECT 1
            FROM order_items oi
            WHERE oi.order_id = p.order_id
              AND oi.ticket_product_id = {product_id}
        );
    """))

    accounts = []
    print("전용 회원 10명의 로그인 준비를 시작합니다.", flush=True)

    for user in users:
        body, _ = request(
            "POST", "/api/auth/login",
            {"email": user["email"], "password": PASSWORD},
            csrf(),
        )
        token = body["data"]["accessToken"]
        payload = token.split(".")[1]
        claims = json.loads(
            base64.urlsafe_b64decode(
                payload + "=" * (-len(payload) % 4)
            )
        )
        require(
            int(claims["sub"]) == user["id"]
            and claims.get("tokenType") == "ACCESS",
            "토큰의 회원 또는 유형이 다릅니다.",
        )
        accounts.append({
            "userId": user["id"],
            "token": token,
            "expiresAt": int(claims["exp"]) * 1000,
        })

    require(
        all(
            account["expiresAt"] > time.time() * 1000 + 120000
            for account in accounts
        ),
        "토큰 잔여 시간이 부족합니다.",
    )

    kst = timezone(timedelta(hours=9))
    visit_date = (
        datetime.now(kst).date() + timedelta(days=1)
    ).isoformat()

    fixture = {
        "preparedAt": datetime.now(timezone.utc).isoformat(),
        "productId": product_id,
        "price": product["price"],
        "initialStock": product["stock"],
        "initialVersion": product["version"],
        "quantity": 1,
        "visitDate": visit_date,
        "userIds": [u["id"] for u in users],
        "before": before,
    }

    local = ROOT / ".local"
    local.mkdir(parents=True, exist_ok=True)
    save_json(local / "load-accounts.json", accounts)
    save_json(local / "load-fixture.json", fixture)

    print(f"준비 완료: 서로 다른 회원 {len(accounts)}명")
    print(f"전용 상품 ID: {product_id} / 시작 재고: {product['stock']}개")
    print(
        f"시작 이력: 주문 {before['orders']}건 / "
        f"항목 {before['items']}건 / 결제 {before['payments']}건"
    )
    print(f"기준 마지막 주문 ID: {before['maxOrderId']}")
    print(f"주문 방문일: {visit_date}")
    print("토큰과 검증 기준을 Git 제외 폴더 .local에 저장했습니다.")
    print("토큰·비밀번호는 출력하지 않았고 주문·재고는 변경하지 않았습니다.")


if __name__ == "__main__":
    main()