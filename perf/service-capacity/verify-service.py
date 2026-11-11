"""통합 부하 결과와 실행 전후 DB 상태를 대조한다."""
import argparse
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "service_state", ROOT / "service-state.py"
)
state_helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(state_helper)


def ensure(condition, message):
    if not condition:
        raise RuntimeError(message)


def save(path, value):
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def verify(folder):
    report = {"passed": False, "correctnessPassed": False}
    try:
        before = json.loads(
            (folder / "db-before.json").read_text(encoding="utf-8")
        )
        run = json.loads(
            (folder / "run.json").read_text(encoding="utf-8")
        )
        fixture = json.loads(
            (folder / "fixture.json").read_text(encoding="utf-8")
        )
        after = state_helper.snapshot()
        save(folder / "db-after.json", after)

        summary = json.loads(
            (folder / "summary.json").read_text(encoding="utf-8")
        )
        metrics = summary["metrics"]

        def count(name):
            return metrics.get(name, {}).get("values", {}).get("count", 0)

        listed = count("service_list_attempts")
        me = count("service_me_attempts")
        attempted = count("service_create_attempts")
        created = count("service_created")
        cancel_attempted = count("service_cancel_attempts")
        cancelled = count("service_cancelled")
        iterations = count("iterations")

        ensure(
            listed > 0 and me > 0 and attempted > 0,
            "일부 사용자 행동이 실행되지 않았습니다.",
        )
        ensure(
            attempted == created == cancel_attempted == cancelled,
            "주문 생성·취소 시도와 성공 건수가 다릅니다.",
        )
        ensure(
            iterations == listed + me + attempted
            == count("service_actions_completed"),
            "행동 완료 건수와 반복 건수가 다릅니다.",
        )
        ensure(
            count("http_reqs") == listed + me + attempted + cancel_attempted,
            "HTTP 요청 건수가 예상과 다릅니다.",
        )

        for name in ("http_req_failed", "service_invalid"):
            ensure(
                metrics[name]["values"]["rate"] == 0,
                "HTTP 또는 행동 검증 실패가 있습니다.",
            )
        checks = metrics["checks"]["values"]
        ensure(
            checks["fails"] == 0
            and checks["passes"]
            == listed * 4 + me * 3 + attempted * 5 + cancel_attempted * 3,
            "응답 검증 건수가 예상과 다릅니다.",
        )
        console = (folder / "console.log").read_text(encoding="utf-8")
        completed = re.findall(
            r"(\d+) complete and (\d+) interrupted iterations", console
        )
        ensure(
            completed and completed[-1] == (str(int(iterations)), "0"),
            "완료·중단 반복 건수 확인이 필요합니다.",
        )
        scenario_vus = re.findall(
            r"scenarios:[^\n]*?([0-9]+) max VUs", console
        )
        ensure(
            scenario_vus == [str(run["vus"])],
            "k6 실행 시나리오의 최대 VU 수가 설정과 다릅니다.",
        )
        if "vus_max" in metrics:
            ensure(
                metrics["vus_max"]["values"]["max"] == run["vus"],
                "기록된 최대 VU 수가 설정과 다릅니다.",
            )
        if run["mode"] == "smoke":
            ensure(
                iterations == run["vus"] * 2
                and listed == run["vus"] * 7 // 10 * 2
                and me == run["vus"] * 2 // 10 * 2
                and attempted == run["vus"] // 10 * 2,
                "기본 검사에서 사용자 배정별 실행 건수가 다릅니다.",
            )

        outcomes = {"created": {}, "cancelled": {}}
        with (folder / "metrics.json").open(encoding="utf-8") as file:
            for line in file:
                point = json.loads(line)
                if (
                    point.get("type") != "Point"
                    or point.get("metric") != "service_order_outcomes"
                ):
                    continue
                data = point["data"]
                tags = data["tags"]
                stage = tags["stage"]
                ensure(stage in outcomes, "주문 기록 단계가 다릅니다.")
                oid = int(tags["order_id"])
                uid = int(tags["user_id"])
                ensure(
                    data["value"] == 1 and oid not in outcomes[stage],
                    "주문 결과 기록이 중복됐습니다.",
                )
                outcomes[stage][oid] = uid

        ensure(
            len(outcomes["created"]) == created
            and outcomes["created"] == outcomes["cancelled"],
            "생성·취소 주문 기록이 일치하지 않습니다.",
        )

        ensure(before["products"] == after["products"], "상품 상태가 변경됐습니다.")
        ensure(before["history"] == after["history"], "조회용 이력이 변경됐습니다.")
        ensure(
            before["counts"]["users"] == after["counts"]["users"],
            "회원 수가 변경됐습니다.",
        )
        for name in ("orders", "items", "payments"):
            ensure(
                after["counts"][name] - before["counts"][name] == created,
                f"{name}: DB 증가량과 생성 건수가 다릅니다.",
            )

        old_rows = {row["orderId"]: row for row in before["writeOrders"]}
        new_rows = {row["orderId"]: row for row in after["writeOrders"]}
        ensure(
            len(old_rows) == len(before["writeOrders"])
            and len(new_rows) == len(after["writeOrders"]),
            "주문별 항목이 하나가 아니거나 결제 관계가 중복됐습니다.",
        )
        ensure(
            all(new_rows.get(oid) == row for oid, row in old_rows.items()),
            "기존 생성·취소 이력이 변경됐습니다.",
        )
        new_ids = set(new_rows) - set(old_rows)
        ensure(
            new_ids == set(outcomes["created"]),
            "DB 신규 주문과 부하 도구의 주문 기록이 다릅니다.",
        )
        writers = {
            account["userId"]
            for account in fixture["accounts"][:run["vus"]]
            if account["group"] == "order_write"
        }
        for oid in new_ids:
            row = new_rows[oid]
            ensure(
                row["userId"] == outcomes["created"][oid]
                and row["userId"] in writers
                and row["status"] == "CANCELLED"
                and row["totalAmount"] == 10000
                and row["visitDate"] == run["visitDate"]
                and row["productId"] == fixture["writeProductId"]
                and row["price"] == 10000
                and row["quantity"] == 1
                and row["paymentId"] is not None
                and row["paymentStatus"] == "CANCELLED"
                and row["paymentAmount"] == 10000,
                f"주문 {oid}: 소유자·상품·금액·상태 검증 실패",
            )

        report.update({
            "correctnessPassed": True,
            "iterations": iterations,
            "listRequests": listed,
            "meRequests": me,
            "created": created,
            "cancelled": cancelled,
            "checksPassed": checks["passes"],
            "newDatabaseOrders": len(new_ids),
        })
        exit_code = int(
            (folder / "exit-code.txt").read_text(encoding="utf-8").strip()
        )
        thresholds_passed = all(
            threshold.get("ok") is True
            for metric in metrics.values()
            for threshold in metric.get("thresholds", {}).values()
        )
        report["thresholdsPassed"] = thresholds_passed
        report["exitCode"] = exit_code
        report["passed"] = exit_code == 0 and thresholds_passed

        print("응답·DB 정확성 검증 통과")
        print(f"주문 생성·취소: 각각 {int(created)}건")
        print(f"응답 검증: {int(checks['passes'])}개 통과")
        print("조회 이력·기존 주문 유지 및 상품 재고 복구 확인")
        print(
            "전체 실행 판정: "
            + ("통과" if report["passed"] else "종료 코드·성능 기준 확인 필요")
        )
        return report
    except Exception as error:
        report["errorType"] = type(error).__name__
        report["error"] = str(error)
        raise
    finally:
        save(folder / "verification.json", report)


def main():
    parser = argparse.ArgumentParser(
        description="통합 부하 결과와 DB 상태를 대조합니다."
    )
    parser.add_argument("run_name")
    args = parser.parse_args()
    ensure(
        re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", args.run_name),
        "결과 이름이 올바르지 않습니다.",
    )
    folder = ROOT / "results" / args.run_name
    ensure(
        not (folder / "verification.json").exists(),
        "기존 검증 결과가 있어 중단합니다.",
    )
    report = verify(folder)
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
