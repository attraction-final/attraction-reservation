"""개인 환경의 재고 5개·회원 10명 동시 주문 정확성 검사."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys
import threading
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "order-list"))

from prepare import BASE, PASSWORD, csrf, request, sql
from run import output

PRODUCT_NAME = "PERF_ORDER_WRITE_RACE_01"
REQUEST_COUNT = 10


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


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
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "toolCommit": output(["git", "rev-parse", "HEAD"]),
        "requestCount": REQUEST_COUNT,
        "passed": False,
    }

    def save():
        (folder / "result.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    try:
        product_id = sql(f"""
            SELECT id FROM ticket_products
            WHERE name = '{PRODUCT_NAME}';
        """)
        require(product_id.isdigit(), "전용 상품이 없거나 중복됐습니다.")
        product_id = int(product_id)

        product = sql(f"""
            SELECT CONCAT(price, ':', stock, ':', status)
            FROM ticket_products WHERE id = {product_id};
        """)
        require(
            product == "10000:5:ON_SALE",
            "상품 상태가 예상과 다릅니다. 재고를 초기화하지 말고 확인하세요.",
        )
        require(
            int(sql(f"""
                SELECT COUNT(*) FROM order_items
                WHERE ticket_product_id = {product_id};
            """)) == 0,
            "이미 사용한 실험 상품입니다. 재실행하지 말고 확인하세요.",
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
        require(len(users) == REQUEST_COUNT, "전용 회원이 10명이 아닙니다.")
        require(
            len({u["id"] for u in users}) == REQUEST_COUNT,
            "회원 ID가 중복됩니다.",
        )
        user_ids = ",".join(str(int(u["id"])) for u in users)

        def counts():
            return {
                "orders": int(sql(f"""
                    SELECT COUNT(*) FROM orders
                    WHERE user_id IN ({user_ids});
                """)),
                "items": int(sql(f"""
                    SELECT COUNT(*) FROM order_items oi
                    JOIN orders o ON o.id = oi.order_id
                    WHERE o.user_id IN ({user_ids});
                """)),
                "payments": int(sql(f"""
                    SELECT COUNT(*) FROM payments p
                    JOIN orders o ON o.id = p.order_id
                    WHERE o.user_id IN ({user_ids});
                """)),
            }

        accounts = []
        for user in users:
            body, _ = request(
                "POST", "/api/auth/login",
                {"email": user["email"], "password": PASSWORD},
                csrf(),
            )
            accounts.append({
                "userId": user["id"],
                "token": body["data"]["accessToken"],
            })

        print("서로 다른 회원 10명 로그인 완료. 토큰은 출력하지 않습니다.")

        kst = timezone(timedelta(hours=9))
        visit_date = (
            datetime.now(kst).date() + timedelta(days=1)
        ).isoformat()
        payload = json.dumps({
            "visitDate": visit_date,
            "items": [{
                "ticketProductId": product_id,
                "quantity": 1,
            }],
        }).encode()

        report.update({
            "productId": product_id,
            "visitDate": visit_date,
            "before": counts(),
        })
        save()

        barrier = threading.Barrier(REQUEST_COUNT)

        def send_order(account):
            result = {"userId": account["userId"]}
            try:
                req = urllib.request.Request(
                    BASE + "/api/orders",
                    data=payload,
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": f"Bearer {account['token']}",
                    },
                    method="POST",
                )
                barrier.wait(timeout=15)

                try:
                    response = urllib.request.urlopen(req, timeout=20)
                except urllib.error.HTTPError as error:
                    response = error

                with response:
                    status = response.status
                    body = json.load(response)

                data = body.get("data") or {}
                result["httpStatus"] = status
                result["orderId"] = data.get("orderId")

                items = data.get("items")
                result["validCreated"] = (
                    status == 201
                    and body.get("success") is True
                    and type(data.get("orderId")) is int
                    and data["orderId"] > 0
                    and data.get("status") == "PENDING"
                    and data.get("totalAmount") == 10000
                    and isinstance(items, list)
                    and len(items) == 1
                    and items[0].get("ticketProductId") == product_id
                    and items[0].get("price") == 10000
                    and items[0].get("quantity") == 1
                )
                result["validRejected"] = (
                    status == 409
                    and body.get("success") is False
                    and body.get("message") == "티켓 상품 재고가 부족합니다."
                )
            except Exception as error:
                result["errorType"] = type(error).__name__
            return result

        print("재고 5개에 주문 요청 10건을 함께 출발시킵니다.")

        with ThreadPoolExecutor(max_workers=REQUEST_COUNT) as executor:
            results = list(executor.map(send_order, accounts))

        report["responses"] = results
        save()

        rows = [
            json.loads(line)
            for line in sql(f"""
                SELECT JSON_OBJECT(
                    'orderId', o.id,
                    'userId', o.user_id,
                    'orderStatus', o.status,
                    'totalAmount', o.total_amount,
                    'visitDate', DATE_FORMAT(o.visit_date, '%Y-%m-%d'),
                    'quantity', oi.quantity,
                    'price', oi.price,
                    'paymentId', p.id,
                    'paymentStatus', p.status,
                    'paymentAmount', p.amount
                )
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                LEFT JOIN payments p ON p.order_id = o.id
                WHERE oi.ticket_product_id = {product_id}
                ORDER BY o.id;
            """).splitlines()
        ]
        stock = int(sql(
            f"SELECT stock FROM ticket_products WHERE id = {product_id};"
        ))
        product_status = sql(
            f"SELECT status FROM ticket_products WHERE id = {product_id};"
        )
        after = counts()
        delta = {
            key: after[key] - report["before"][key]
            for key in after
        }

        successes = [r for r in results if r.get("validCreated")]
        rejections = [r for r in results if r.get("validRejected")]

        report.update({
            "after": after,
            "delta": delta,
            "stock": stock,
            "productStatus": product_status,
            "databaseRows": rows,
            "successCount": len(successes),
            "rejectedCount": len(rejections),
        })
        save()

        print(f"정상 주문 성공: {len(successes)}건")
        print(f"정상 재고 부족 거절: {len(rejections)}건")
        print(f"최종 재고: {stock}개 / 상품 상태: {product_status}")
        print(
            f"증가한 데이터: 주문 {delta['orders']}건 / "
            f"항목 {delta['items']}건 / 결제 {delta['payments']}건"
        )

        require(
            len(successes) == 5 and len(rejections) == 5,
            "성공·재고 부족 거절 건수가 예상과 다릅니다.",
        )
        require(
            stock == 0 and product_status == "OUT_OF_STOCK",
            "최종 재고 또는 상품 상태가 다릅니다.",
        )
        require(
            delta == {"orders": 5, "items": 5, "payments": 5},
            "주문·항목·결제 증가 건수가 다릅니다.",
        )
        require(len(rows) == 5, "상품 관련 DB 행 수가 다릅니다.")

        expected_owners = {
            r["orderId"]: r["userId"] for r in successes
        }
        require(len(expected_owners) == 5, "성공 주문 ID가 중복됐습니다.")
        require(
            {r["orderId"] for r in rows} == set(expected_owners),
            "성공 응답과 저장된 주문 ID가 다릅니다.",
        )

        for row in rows:
            require(
                row["userId"] == expected_owners[row["orderId"]]
                and row["orderStatus"] == "PENDING"
                and row["totalAmount"] == 10000
                and row["visitDate"] == visit_date
                and row["quantity"] == 1
                and row["price"] == 10000
                and row["paymentId"] is not None
                and row["paymentStatus"] == "PENDING"
                and row["paymentAmount"] == 10000,
                "주문 소유자·상태·금액·항목·결제 정보가 다릅니다.",
            )

        report["passed"] = True
        print("동시 주문 정확성 검사 통과. 초과 판매 없이 재고만큼 성공했습니다.")
        print("생성된 주문은 취소하거나 삭제하지 않고 보관합니다.")
    except Exception as error:
        report["errorType"] = type(error).__name__
        raise
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        save()

    print("결과 폴더:", folder)


if __name__ == "__main__":
    main()