"""집중 요청 실행별 상품과 검증 기준을 준비한다."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent

spec = importlib.util.spec_from_file_location(
    "burst_users", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write_json(path, value):
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser(
        description="새 실험 상품과 실행 전 검증 기준을 준비합니다."
    )
    parser.add_argument("run_name")
    parser.add_argument("--users", type=int, required=True)
    args = parser.parse_args()

    require(
        re.fullmatch(r"[A-Za-z0-9_-]{1,48}", args.run_name),
        "실행 이름은 영문·숫자·밑줄·하이픈 1~48자로 지정하세요.",
    )
    require(1 <= args.users <= 2000, "회원 수는 1~2000명이어야 합니다.")
    require(
        helper.sql("SELECT DATABASE();")
        == "attraction_order_create_burst_perf",
        "주문 집중 요청 전용 DB가 아닙니다.",
    )

    active = ROOT / ".local/active-run.json"
    require(
        not active.exists(),
        "이전 실행의 검증·취소 정리가 끝나지 않았습니다.",
    )

    accounts = json.loads(
        (ROOT / ".local/accounts.json").read_text(encoding="utf-8")
    )[:args.users]
    require(len(accounts) == args.users, "회원 토큰이 부족합니다.")
    user_ids = [account["expected"]["id"] for account in accounts]
    require(
        all(type(user_id) is int and user_id > 0 for user_id in user_ids)
        and len(set(user_ids)) == args.users,
        "회원 ID가 잘못됐거나 중복됐습니다.",
    )
    require(
        min(account["expiresAt"] for account in accounts)
        > time.time() * 1000 + 120000,
        "토큰을 다시 준비해 주세요. 남은 시간이 2분 미만입니다.",
    )
    require(
        int(helper.sql(
            "SELECT COUNT(*) FROM orders WHERE status = 'PENDING';"
        )) == 0,
        "전용 DB에 미취소 주문이 남아 있습니다.",
    )

    product_name = "PERF_ORDER_BURST_" + args.run_name
    require(
        int(helper.sql(
            "SELECT COUNT(*) FROM ticket_products "
            f"WHERE name = '{product_name}';"
        )) == 0,
        "이미 사용한 상품 이름입니다. 다른 실행 이름을 사용하세요.",
    )

    jar = REPO / "build/libs/attraction-reservation-0.0.1-SNAPSHOT.jar"
    require(jar.is_file(), "앱 실행 파일이 없습니다.")

    folder = ROOT / "results" / args.run_name
    folder.mkdir(parents=True, exist_ok=False)

    for source in [
        ROOT / "application-perf.yaml",
        ROOT / "order-create-burst.js",
        *sorted(ROOT.glob("*.py")),
    ]:
        (folder / source.name).write_bytes(source.read_bytes())

    (folder / "source-commit.txt").write_text(
        subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO, text=True, encoding="utf-8",
        ),
        encoding="utf-8",
    )
    (folder / "source-change.patch").write_text(
        subprocess.check_output(
            ["git", "diff", "HEAD", "--", "src/main/java", "src/test/java"],
            cwd=REPO, text=True, encoding="utf-8",
        ),
        encoding="utf-8",
    )
    (folder / "app-jar-sha256.txt").write_text(
        hashlib.sha256(jar.read_bytes()).hexdigest() + "\n",
        encoding="utf-8",
    )

    # 준비·실행 중 중단돼도 다음 실행이 덮어쓰지 못하도록 남긴다.
    with active.open("x", encoding="utf-8") as file:
        json.dump({
            "runName": args.run_name,
            "productName": product_name,
        }, file, ensure_ascii=False, indent=2)

    helper.sql(f"""
        INSERT INTO ticket_products (
            created_at, updated_at, name, description,
            price, stock, status, type, version
        )
        VALUES (
            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
            '{product_name}', '주문 집중 요청 실행별 전용 상품',
            10000, 10000, 'ON_SALE', 'ALL_DAY', 0
        );
    """)

    product_id = helper.sql(
        "SELECT id FROM ticket_products "
        f"WHERE name = '{product_name}';"
    )
    require(product_id.isdigit(), "실험 상품 ID 확인에 실패했습니다.")

    before = json.loads(helper.sql("""
        SELECT JSON_OBJECT(
            'orders', (SELECT COUNT(*) FROM orders),
            'items', (SELECT COUNT(*) FROM order_items),
            'payments', (SELECT COUNT(*) FROM payments),
            'maxOrderId', (SELECT COALESCE(MAX(id), 0) FROM orders)
        );
    """))

    kst = timezone(timedelta(hours=9))
    fixture = {
        "runName": args.run_name,
        "users": args.users,
        "userIds": user_ids,
        "productId": int(product_id),
        "productName": product_name,
        "price": 10000,
        "quantity": 1,
        "initialStock": 10000,
        "visitDate": (
            datetime.now(kst).date() + timedelta(days=1)
        ).isoformat(),
        "preparedAt": datetime.now(timezone.utc).isoformat(),
        "before": before,
    }
    write_json(folder / "fixture.json", fixture)
    write_json(ROOT / ".local/burst-fixture.json", fixture)

    print(f"실행 준비 완료: {args.run_name} / 회원 {args.users}명")
    print(f"전용 상품 ID: {product_id} / 시작 재고: 10000개")
    print(f"결과 폴더: {folder}")
    print("아직 주문 요청은 보내지 않았습니다.")
    print("검증·취소 정리가 끝날 때까지 다음 실행을 차단합니다.")


if __name__ == "__main__":
    main()
