"""주문 부하 실험용 로그인 토큰과 상품 배정 준비."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "capacity_users", ROOT / "prepare-users.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def save_private(name, value):
    target = ROOT / ".local" / name
    temporary = target.with_suffix(".tmp")
    fd = os.open(
        temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600
    )
    with os.fdopen(fd, "w", encoding="utf-8") as file:
        json.dump(value, file, ensure_ascii=False, indent=2)
        file.write("\n")
    temporary.replace(target)


def main():
    require(
        helper.sql("SELECT DATABASE();")
        == "attraction_order_write_capacity_perf",
        "주문 실험 전용 DB가 아닙니다.",
    )

    users = [
        json.loads(line)
        for line in helper.sql("""
            SELECT JSON_OBJECT('id', id, 'email', email)
            FROM users
            WHERE email REGEXP '^perfwrite[0-9]{4}@example[.]test$'
              AND role = 'USER'
              AND status = 'ACTIVE'
              AND provider = 'LOCAL'
            ORDER BY email;
        """).splitlines()
    ]
    require(len(users) == 100, "실험용 회원 100명이 필요합니다.")

    products = [
        json.loads(line)
        for line in helper.sql("""
            SELECT JSON_OBJECT(
                'id', id, 'name', name, 'price', price,
                'stock', stock, 'status', status
            )
            FROM ticket_products
            WHERE name = 'PERF_WRITE_CAPACITY_SHARED'
               OR name REGEXP '^PERF_WRITE_CAPACITY_SPLIT_[0-9]{4}$'
            ORDER BY name;
        """).splitlines()
    ]
    by_name = {product["name"]: product for product in products}
    names = ["PERF_WRITE_CAPACITY_SHARED"] + [
        f"PERF_WRITE_CAPACITY_SPLIT_{number:04d}"
        for number in range(100)
    ]
    require(
        len(products) == 101 and set(by_name) == set(names),
        "실험 상품이 누락됐거나 이름이 중복됐습니다.",
    )
    require(
        all(
            product["price"] == 10000
            and product["stock"] == 10000
            and product["status"] == "ON_SALE"
            for product in products
        ),
        "상품 가격·재고·상태가 실험 조건과 다릅니다.",
    )

    accounts = []
    for number, user in enumerate(users):
        _, body, _ = helper.request(
            "POST", "/api/auth/login",
            {"email": user["email"], "password": helper.PASSWORD},
            helper.csrf_headers(),
        )
        require(body.get("success") is True, "로그인에 실패했습니다.")
        token = body["data"]["accessToken"]
        payload = token.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(
            payload + "=" * (-len(payload) % 4)
        ))
        require(
            int(claims["sub"]) == user["id"]
            and claims.get("tokenType") == "ACCESS",
            "토큰의 회원 또는 유형이 다릅니다.",
        )
        accounts.append({
            "userId": user["id"],
            "token": token,
            "expiresAt": int(claims["exp"]) * 1000,
            "sharedProductId": by_name[names[0]]["id"],
            "splitProductId": by_name[
                f"PERF_WRITE_CAPACITY_SPLIT_{number:04d}"
            ]["id"],
        })
        if (number + 1) % 25 == 0:
            print(f"로그인 준비: {number + 1}/100명", flush=True)

    require(
        min(account["expiresAt"] for account in accounts)
        > time.time() * 1000 + 120000,
        "토큰 잔여 시간이 부족합니다. 다시 준비해 주세요.",
    )
    save_private("load-accounts.json", accounts)
    save_private("products.json", products)
    print("준비 완료: 서로 다른 회원 100명 / 공통 상품 1개 / 분산 상품 100개")
    print("토큰과 상품 배정을 Git 제외 폴더 .local에 저장했습니다.")
    print("토큰·비밀번호 원문은 출력하지 않았고 주문·재고는 변경하지 않았습니다.")


if __name__ == "__main__":
    main()
