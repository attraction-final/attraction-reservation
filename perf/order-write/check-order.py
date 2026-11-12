"""개인 환경의 주문 생성·취소·재취소 정확성 검사."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "order-list"))

from prepare import BASE, PASSWORD, csrf, request, sql
from run import output


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def call(method, path, token, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        BASE + path,
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
        method=method,
    )
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error

    with response:
        return response.status, json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_id")
    args = parser.parse_args()

    require(
        re.fullmatch(r"[a-zA-Z0-9_-]{1,60}", args.run_id),
        "실행 ID 형식이 올바르지 않습니다.",
    )
    require(
        sql("SELECT DATABASE();") == "attraction_perf",
        "개인 테스트 DB가 아닙니다.",
    )

    folder = ROOT / "results" / args.run_id
    folder.mkdir(parents=True, exist_ok=False)

    report = {
        "runId": args.run_id,
        "toolCommit": output(["git", "rev-parse", "HEAD"]),
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "passed": False,
        "steps": [],
    }

    def save():
        (folder / "result.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def record(label, status, state):
        report["steps"].append({
            "step": label,
            "httpStatus": status,
            "state": state,
        })
        save()

    try:
        user_id = sql("""
            SELECT id FROM users
            WHERE email = 'perfwrite0000@example.test'
              AND role = 'USER'
              AND status = 'ACTIVE'
              AND provider = 'LOCAL';
        """)
        require(user_id.isdigit(), "전용 테스트 회원이 없습니다.")
        user_id = int(user_id)

        product_id = sql("""
            SELECT id FROM ticket_products
            WHERE name = 'PERF_ORDER_WRITE_SMOKE';
        """)
        require(product_id.isdigit(), "전용 상품이 없거나 중복됐습니다.")
        product_id = int(product_id)

        product = sql(f"""
            SELECT CONCAT(price, ':', stock, ':', status)
            FROM ticket_products WHERE id = {product_id};
        """)
        require(
            product == "10000:100:ON_SALE",
            "상품 가격·재고·상태가 예상과 다릅니다. 초기화하지 말고 확인하세요.",
        )

        body, _ = request(
            "POST", "/api/auth/login",
            {"email": "perfwrite0000@example.test", "password": PASSWORD},
            csrf(),
        )
        token = body["data"]["accessToken"]

        kst = timezone(timedelta(hours=9))
        visit_date = (
            datetime.now(kst).date() + timedelta(days=1)
        ).isoformat()

        report.update({
            "userId": user_id,
            "productId": product_id,
            "visitDate": visit_date,
            "initialStock": 100,
        })
        save()

        def snapshot(order_id):
            state = json.loads(sql(f"""
                SELECT JSON_OBJECT(
                    'userId', o.user_id,
                    'orderStatus', o.status,
                    'totalAmount', o.total_amount,
                    'visitDate', DATE_FORMAT(o.visit_date, '%Y-%m-%d'),
                    'paymentCount', (
                        SELECT COUNT(*) FROM payments
                        WHERE order_id = o.id
                    ),
                    'paymentStatus', (
                        SELECT MAX(status) FROM payments
                        WHERE order_id = o.id
                    ),
                    'paymentAmount', (
                        SELECT MAX(amount) FROM payments
                        WHERE order_id = o.id
                    ),
                    'itemCount', (
                        SELECT COUNT(*) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'quantity', (
                        SELECT SUM(quantity) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'price', (
                        SELECT MAX(price) FROM order_items
                        WHERE order_id = o.id
                    ),
                    'productId', (
                        SELECT MAX(ticket_product_id) FROM order_items
                        WHERE order_id = o.id
                    )
                )
                FROM orders o WHERE o.id = {order_id};
            """))
            state["stock"] = int(sql(
                f"SELECT stock FROM ticket_products WHERE id = {product_id};"
            ))
            return state

        def verify(state, expected_status, expected_stock):
            expected = {
                "userId": user_id,
                "orderStatus": expected_status,
                "totalAmount": 10000,
                "visitDate": visit_date,
                "paymentCount": 1,
                "paymentStatus": expected_status,
                "paymentAmount": 10000,
                "itemCount": 1,
                "quantity": 1,
                "price": 10000,
                "productId": product_id,
                "stock": expected_stock,
            }
            require(state == expected, "DB 상태가 예상과 다릅니다.")

        status, body = call(
            "POST", "/api/orders", token,
            {
                "visitDate": visit_date,
                "items": [{
                    "ticketProductId": product_id,
                    "quantity": 1,
                }],
            },
        )
        require(
            status == 201 and body.get("success") is True,
            f"주문 생성 실패: HTTP {status}",
        )
        data = body["data"]
        order_id = data["orderId"]
        require(
            type(order_id) is int and order_id > 0,
            "응답 주문 ID가 올바르지 않습니다.",
        )
        report["orderId"] = order_id
        save()

        state = snapshot(order_id)
        record("주문 생성", status, state)
        verify(state, "PENDING", 99)
        require(
            data["status"] == "PENDING" and data["totalAmount"] == 10000,
            "주문 생성 응답의 상태 또는 금액이 다릅니다.",
        )
        print(f"주문 생성: 201 / 주문 ID {order_id} / 재고 99 확인")

        path = f"/api/orders/{order_id}/cancel"
        status, body = call("POST", path, token)
        state = snapshot(order_id)
        record("주문 취소", status, state)
        require(
            status == 200
            and body.get("success") is True
            and body["data"]["orderId"] == order_id
            and body["data"]["status"] == "CANCELLED",
            f"주문 취소 응답이 예상과 다릅니다: HTTP {status}",
        )
        verify(state, "CANCELLED", 100)
        print("주문 취소: 200 / 주문·결제 CANCELLED / 재고 100 확인")

        status, body = call("POST", path, token)
        state = snapshot(order_id)
        record("재취소", status, state)
        require(
            status == 409 and body.get("success") is False,
            f"재취소가 예상대로 거절되지 않았습니다: HTTP {status}",
        )
        verify(state, "CANCELLED", 100)
        print("재취소: 409 / 재고 100 유지 확인")

        report["passed"] = True
        print("전체 검사 통과. 주문·항목·결제 이력은 DB에 유지합니다.")
    except Exception as error:
        report["errorType"] = type(error).__name__
        raise
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        save()

    print("결과 폴더:", folder)
    print("토큰과 비밀번호는 결과 파일에 저장하지 않았습니다.")


if __name__ == "__main__":
    main()