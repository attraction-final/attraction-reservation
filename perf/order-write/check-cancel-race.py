"""같은 주문에 취소 요청 10건을 보내 중복 재고 복구를 검사한다."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
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

REQUEST_COUNT = 10


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_id")
    parser.add_argument("--source-run", default="order-write-race-01")
    args = parser.parse_args()

    for value in (args.run_id, args.source_run):
        require(
            re.fullmatch(r"[a-zA-Z0-9_-]{1,60}", value),
            "실행 ID 형식이 올바르지 않습니다.",
        )

    require(
        sql("SELECT DATABASE();") == "attraction_perf",
        "개인 테스트 DB가 아닙니다.",
    )

    source = json.loads(
        (ROOT / "results" / args.source_run / "result.json")
        .read_text(encoding="utf-8")
    )
    require(source.get("passed") is True, "앞선 동시 주문 검사가 통과하지 않았습니다.")

    product_id = int(source["productId"])
    source_orders = source["databaseRows"]
    require(len(source_orders) == 5, "원본 주문 수가 예상과 다릅니다.")

    target = min(source_orders, key=lambda row: row["orderId"])
    order_id = int(target["orderId"])
    user_id = int(target["userId"])
    expected_ids = {int(row["orderId"]) for row in source_orders}

    folder = ROOT / "results" / args.run_id
    folder.mkdir(parents=True, exist_ok=False)

    report = {
        "runId": args.run_id,
        "sourceRun": args.source_run,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "toolCommit": output(["git", "rev-parse", "HEAD"]),
        "productId": product_id,
        "orderId": order_id,
        "userId": user_id,
        "requestCount": REQUEST_COUNT,
        "passed": False,
    }

    def save():
        (folder / "result.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def snapshot():
        product = json.loads(sql(f"""
            SELECT JSON_OBJECT(
                'stock', stock,
                'status', status,
                'version', version
            )
            FROM ticket_products
            WHERE id = {product_id}
              AND name = 'PERF_ORDER_WRITE_RACE_01';
        """))
        orders = [
            json.loads(line)
            for line in sql(f"""
                SELECT JSON_OBJECT(
                    'orderId', o.id,
                    'userId', o.user_id,
                    'orderStatus', o.status,
                    'totalAmount', o.total_amount,
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
        return {"product": product, "orders": orders}

    try:
        before = snapshot()
        report["before"] = before
        save()

        require(
            before["product"]["stock"] == 0
            and before["product"]["status"] == "OUT_OF_STOCK",
            "실험 시작 재고 또는 상품 상태가 다릅니다.",
        )
        require(
            len(before["orders"]) == 5
            and {row["orderId"] for row in before["orders"]} == expected_ids,
            "원본과 현재 주문 목록이 다릅니다.",
        )
        require(
            all(
                row["orderStatus"] == "PENDING"
                and row["paymentStatus"] == "PENDING"
                and row["quantity"] == 1
                and row["price"] == 10000
                and row["totalAmount"] == 10000
                and row["paymentAmount"] == 10000
                and row["paymentId"] is not None
                for row in before["orders"]
            ),
            "주문·항목·결제의 시작 상태가 예상과 다릅니다.",
        )

        email = sql(f"""
            SELECT email FROM users
            WHERE id = {user_id}
              AND email REGEXP '^perfwrite[0-9]{{4}}@example[.]test$'
              AND role = 'USER'
              AND status = 'ACTIVE'
              AND provider = 'LOCAL';
        """)
        require(bool(email), "활성 테스트 주문 소유자가 없습니다.")

        body, _ = request(
            "POST", "/api/auth/login",
            {"email": email, "password": PASSWORD},
            csrf(),
        )
        token = body["data"]["accessToken"]
        barrier = threading.Barrier(REQUEST_COUNT)

        def cancel(_):
            result = {}
            try:
                req = urllib.request.Request(
                    BASE + f"/api/orders/{order_id}/cancel",
                    headers={"Authorization": f"Bearer {token}"},
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
                result["validCancelled"] = (
                    status == 200
                    and body.get("success") is True
                    and data.get("orderId") == order_id
                    and data.get("status") == "CANCELLED"
                )
                result["validRejected"] = (
                    status == 409
                    and body.get("success") is False
                    and body.get("message")
                    == "결제 대기 상태의 주문만 취소할 수 있습니다."
                )
            except Exception as error:
                result["errorType"] = type(error).__name__
            return result

        print(f"대상 주문 ID: {order_id}")
        print("주문 소유자 1명의 토큰으로 취소 요청 10건을 함께 출발시킵니다.")

        with ThreadPoolExecutor(max_workers=REQUEST_COUNT) as executor:
            results = list(executor.map(cancel, range(REQUEST_COUNT)))

        report["responses"] = results
        save()

        after = snapshot()
        successes = sum(bool(r.get("validCancelled")) for r in results)
        rejections = sum(bool(r.get("validRejected")) for r in results)

        report.update({
            "after": after,
            "successCount": successes,
            "rejectedCount": rejections,
        })
        save()

        print(f"정상 취소 성공: {successes}건")
        print(f"정상 취소 불가 거절: {rejections}건")
        print(
            f"최종 재고: {after['product']['stock']}개 / "
            f"상품 상태: {after['product']['status']}"
        )

        require(
            successes == 1 and rejections == 9,
            "취소 성공·거절 건수가 예상과 다릅니다.",
        )
        require(
            after["product"]["stock"] == 1
            and after["product"]["status"] == "ON_SALE",
            "재고 복구 또는 상품 상태가 예상과 다릅니다.",
        )

        expected_rows = []
        for row in before["orders"]:
            expected = dict(row)
            if row["orderId"] == order_id:
                require(row["userId"] == user_id, "주문 소유자가 다릅니다.")
                expected["orderStatus"] = "CANCELLED"
                expected["paymentStatus"] = "CANCELLED"
            expected_rows.append(expected)

        require(
            after["orders"] == expected_rows,
            "대상 주문 외의 변경 또는 주문·결제·항목 불일치가 있습니다.",
        )

        report["passed"] = True
        print("대상 주문·결제 CANCELLED 및 나머지 주문 4건 유지 확인")
        print("동시 취소 정확성 검사 통과. 재고는 한 번만 복구됐습니다.")
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