"""실패한 통합 실험의 미취소 주문만 API로 취소한다."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parent


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def ensure(condition, message):
    if not condition:
        raise RuntimeError(message)


def save(path, value):
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser(
        description="실험 주문을 취소하고 재고와 이력 보존을 검사합니다."
    )
    parser.add_argument("run_name")
    args = parser.parse_args()
    ensure(
        re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", args.run_name),
        "실행 이름이 올바르지 않습니다.",
    )

    folder = ROOT / "results" / args.run_name
    lock = ROOT / ".local/active-run.json"
    ensure(
        json.loads(lock.read_text(encoding="utf-8"))["runName"]
        == args.run_name,
        "차단된 실행 이름과 다릅니다.",
    )
    state = load_module("cleanup_state", ROOT / "service-state.py")
    helper = state.helper

    baseline = json.loads(
        (folder / "db-before.json").read_text(encoding="utf-8")
    )
    recorded = json.loads(
        (folder / "db-after.json").read_text(encoding="utf-8")
    )
    fixture = json.loads(
        (folder / "fixture.json").read_text(encoding="utf-8")
    )
    run = json.loads(
        (folder / "run.json").read_text(encoding="utf-8")
    )
    writers = {
        account["userId"]: account
        for account in fixture["accounts"][:run["vus"]]
        if account["group"] == "order_write"
    }

    before = state.snapshot()
    ensure(
        before["counts"] == recorded["counts"]
        and before["history"] == baseline["history"],
        "실패 결과 이후 건수 또는 조회 이력이 변경됐습니다.",
    )

    def rows_by_id(snapshot):
        rows = snapshot["writeOrders"]
        result = {row["orderId"]: row for row in rows}
        ensure(len(rows) == len(result), "주문 관계가 중복됐습니다.")
        return result

    old = rows_by_id(baseline)
    failed = rows_by_id(recorded)
    current = rows_by_id(before)
    ensure(
        set(current) == set(failed),
        "실패 결과 이후 생성·취소용 주문 목록이 변경됐습니다.",
    )
    ensure(
        all(current.get(oid) == row for oid, row in old.items()),
        "기존 실행의 주문 이력이 변경됐습니다.",
    )

    new_ids = set(failed) - set(old)
    pending = []
    for oid in sorted(new_ids):
        row = current[oid]
        ensure(
            row["userId"] in writers
            and row["productId"] == fixture["writeProductId"]
            and row["visitDate"] == run["visitDate"]
            and row["totalAmount"] == 10000
            and row["price"] == 10000
            and row["quantity"] == 1
            and row["paymentId"] is not None
            and row["paymentAmount"] == 10000,
            f"주문 {oid}: 정리 대상 조건이 다릅니다.",
        )
        ensure(
            row == {
                **failed[oid],
                "status": row["status"],
                "paymentStatus": row["paymentStatus"],
            },
            f"주문 {oid}: 상태 이외의 정보가 변경됐습니다.",
        )
        ensure(
            (row["status"], row["paymentStatus"])
            in (("PENDING", "PENDING"), ("CANCELLED", "CANCELLED")),
            f"주문 {oid}: 별도 확인이 필요한 상태입니다.",
        )
        if row["status"] == "PENDING":
            pending.append(row)

    product = next(
        row for row in before["products"]
        if row["id"] == fixture["writeProductId"]
    )
    initial_product = next(
        row for row in baseline["products"]
        if row["id"] == fixture["writeProductId"]
    )
    ensure(
        product["stock"] + len(pending) == initial_product["stock"],
        "미취소 주문 수와 재고 차이가 일치하지 않습니다.",
    )

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    save(folder / f"cleanup-before-{stamp}.json", before)
    shutil.copy2(
        ROOT / "cleanup-service.py",
        folder / f"cleanup-service-{stamp}.py",
    )

    def record(value):
        value["recordedAt"] = datetime.now(timezone.utc).isoformat()
        with (folder / "cleanup-attempts.jsonl").open(
            "a", encoding="utf-8"
        ) as file:
            file.write(json.dumps(value, ensure_ascii=False) + "\n")

    print(f"확인된 미취소 실험 주문: {len(pending)}건", flush=True)
    sessions = {}
    try:
        for number, row in enumerate(pending, 1):
            uid = row["userId"]
            oid = row["orderId"]
            if uid not in sessions:
                user = writers[uid]["expected"]
                status, body, _ = helper.request(
                    "POST", "/api/auth/login",
                    {
                        "email": user["email"],
                        "password": helper.PASSWORD,
                    },
                    helper.csrf_headers(),
                )
                data = body.get("data") or {}
                token = data.get("accessToken")
                ensure(
                    status == 200 and body.get("success") is True
                    and isinstance(token, str) and bool(token),
                    f"회원 {uid}: 정리용 로그인 실패",
                )
                sessions[uid] = token

            record({"event": "취소 요청", "userId": uid, "orderId": oid})
            status, body, _ = helper.request(
                "POST", f"/api/orders/{oid}/cancel",
                headers={"Authorization": f"Bearer {sessions[uid]}"},
            )
            result = body.get("data") or {}
            ensure(
                status == 200 and body.get("success") is True
                and result.get("orderId") == oid
                and result.get("status") == "CANCELLED",
                f"주문 {oid}: 취소 응답 검증 실패",
            )
            record({"event": "취소 응답 확인", "orderId": oid})
            if number % 25 == 0 or number == len(pending):
                print(
                    f"취소 진행: {number}/{len(pending)}건",
                    flush=True,
                )

        after = state.snapshot()
        save(folder / "cleanup-db-after.json", after)
        final = rows_by_id(after)

        ensure(
            after["counts"] == before["counts"]
            and after["history"] == before["history"]
            and after["products"] == baseline["products"]
            and set(final) == set(current),
            "건수·조회 이력·상품 재고 복구 검증 실패",
        )
        for oid, row in current.items():
            expected = (
                {**row, "status": "CANCELLED", "paymentStatus": "CANCELLED"}
                if oid in new_ids else row
            )
            ensure(final[oid] == expected, f"주문 {oid}: 최종 상태 검증 실패")

        save(folder / "cleanup.json", {
            "passed": True,
            "cancelledThisAttempt": len(pending),
            "verifiedRunOrders": len(new_ids),
            "stockRestored": True,
            "historyPreserved": True,
            "finishedAt": datetime.now(timezone.utc).isoformat(),
            "note": "정리 완료이며 원래 부하 실행의 실패 판정은 유지합니다.",
        })
        lock.unlink()
        print("실험 주문·결제 취소와 재고 복구 검증 통과")
        print("주문·항목·결제 건수 및 조회 이력 유지 확인")
        print("실행 차단 해제 완료 / 원래 부하 결과는 실패로 유지")
        print("토큰·쿠키·비밀번호 원문은 출력하거나 저장하지 않았습니다.")
    except Exception as error:
        record({"event": "정리 중단", "errorType": type(error).__name__})
        print("정리가 중단됐습니다. 실행 차단을 유지합니다.")
        print("응답을 받지 못해도 취소됐을 수 있으므로 상태부터 확인하세요.")
        raise


if __name__ == "__main__":
    main()
