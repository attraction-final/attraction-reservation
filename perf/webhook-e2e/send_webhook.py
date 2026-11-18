"""PortOne V2 형식의 웹훅을 Standard Webhooks 방식으로 서명해 보낸다.

    python send_webhook.py <pgId> <webhookId> [--bad-signature]

서명: HMAC-SHA256(키 = whsec_ 뒤 Base64 디코드, 메시지 = "{id}.{timestamp}.{body}"), 헤더 값 "v1,<Base64>".
compose.yml의 PORTONE_WEBHOOK_SECRET과 같은 키를 쓴다.
"""
import base64
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request

SECRET = "whsec_ZTJlLW9ubHktd2ViaG9vay1zZWNyZXQta2V5LTMyYg=="
URL = "http://localhost:18080/api/payments/webhooks/portone"


def main():
    pg_id, webhook_id = sys.argv[1], sys.argv[2]
    bad = "--bad-signature" in sys.argv
    timestamp = str(int(time.time()))
    body = json.dumps({
        "type": "Transaction.Paid",
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "data": {"storeId": "store-e2e", "paymentId": pg_id, "transactionId": f"tx-{pg_id}"},
    }, separators=(",", ":"))

    key = base64.b64decode(SECRET.removeprefix("whsec_"))
    if bad:
        key = b"wrong-key-" + key
    digest = hmac.new(key, f"{webhook_id}.{timestamp}.{body}".encode(), hashlib.sha256).digest()
    signature = "v1," + base64.b64encode(digest).decode()

    request = urllib.request.Request(URL, data=body.encode(), method="POST", headers={
        "Content-Type": "application/json",
        "webhook-id": webhook_id,
        "webhook-timestamp": timestamp,
        "webhook-signature": signature,
    })
    try:
        with urllib.request.urlopen(request) as response:
            print(f"HTTP {response.status} {response.read().decode()}")
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} {e.read().decode()}")


if __name__ == "__main__":
    main()
