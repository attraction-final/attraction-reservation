"""개인 테스트 환경 전용 회원/목록 조회 fixture. 주문 생성 업무 검증에는 사용하지 않는다."""
import argparse
import base64
from datetime import datetime, timedelta, timezone
from http.cookies import SimpleCookie
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
BASE = 'http://127.0.0.1:18086'
PASSWORD = 'PerfLocal123!'

def sql(query, admin=False):
    result = subprocess.run(['docker', 'compose', '-f', str(ROOT / 'compose.yaml'),
        'exec', '-T', 'mysql', 'mysql', '-uroot' if admin else '-uperf', '-pperf-local-only',
        '-N', '-B', 'attraction_perf'], input=query, text=True, capture_output=True, check=True)
    return result.stdout.strip()

def request(method, path, payload=None, headers=None):
    data = None if payload is None else json.dumps(payload).encode()
    h = {'Content-Type': 'application/json', **(headers or {})}
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, headers=h, method=method), timeout=20) as r:
        return json.load(r), r.headers

def csrf():
    body, headers = request('GET', '/api/auth/csrf')
    cookie = SimpleCookie()
    for value in headers.get_all('Set-Cookie', []):
        cookie.load(value)
    # 로컬 HTTP 전용 클라이언트가 Secure 쿠키를 명시 전달한다. 앱 보안 설정은 변경하지 않는다.
    return {body['data']['headerName']: body['data']['token'],
        'Cookie': '; '.join(f'{k}={v.value}' for k, v in cookie.items())}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seed', action='store_true')
    parser.add_argument('--extend', action='store_true')
    parser.add_argument('--users', type=int, default=20)
    parser.add_argument('--orders-per-user', type=int, default=1000)
    args = parser.parse_args()
    if not 2 <= args.users <= 2000 or not 10 <= args.orders_per_user <= 100000:
        raise ValueError('회원 2~2000명, 회원별 주문 10~100000건 범위')
    if sql('SELECT DATABASE();') != 'attraction_perf':
        raise RuntimeError('개인 테스트 DB가 아닙니다.')
    if args.seed and args.extend:
        raise ValueError('--seed와 --extend는 함께 사용할 수 없습니다.')

    ids = []
    if args.seed or args.extend:
        if args.seed and int(sql('SELECT COUNT(*) FROM orders;')):
            raise RuntimeError(
                '기존 주문이 있는 DB입니다. 데이터 추가는 --extend를 사용하세요.'
            )

        # 기존 회원의 주문 수가 다르면 임의로 덮어쓰거나 추가하지 않는다.
        for i in range(args.users):
            email = f'perf{i:04d}@example.test'
            uid = sql(f"SELECT id FROM users WHERE email='{email}';")
            if uid:
                total = int(sql(
                    f'SELECT COUNT(*) FROM orders WHERE user_id={int(uid)};'
                ))
                if total not in (0, args.orders_per_user):
                    raise RuntimeError(
                        f'회원 {uid}의 주문 수가 {total}건입니다. '
                        f'예상한 {args.orders_per_user}건과 달라 중단합니다.'
                    )

        start = datetime(2025, 1, 1, tzinfo=timezone.utc)

        for i in range(args.users):
            email = f'perf{i:04d}@example.test'
            uid = sql(f"SELECT id FROM users WHERE email='{email}';")

            if not uid:
                request(
                    'POST',
                    '/api/users/signup',
                    {
                        'email': email,
                        'password': PASSWORD,
                        'nickname': f'성능테스트{i:04d}',
                    },
                    csrf(),
                )
                uid = sql(f"SELECT id FROM users WHERE email='{email}';")

            uid = int(uid)
            ids.append((uid, email))
            total = int(sql(
                f'SELECT COUNT(*) FROM orders WHERE user_id={uid};'
            ))

            # 이미 준비된 회원의 주문은 그대로 유지한다.
            if total == args.orders_per_user:
                continue

            if total != 0:
                raise RuntimeError(
                    f'회원 {uid}의 주문 수가 변경되어 중단합니다.'
                )

            for batch in range(0, args.orders_per_user, 500):
                values = []
                for j in range(
                    batch,
                    min(batch + 500, args.orders_per_user),
                ):
                    stamp = (
                        start + timedelta(seconds=j // 2)
                    ).strftime('%Y-%m-%d %H:%M:%S')

                    values.append(
                        f"({uid},10000,'COMPLETED','2025-01-01',"
                        f"'{stamp}','{stamp}')"
                    )

                sql(
                    'INSERT INTO orders'
                    '(user_id,total_amount,status,visit_date,'
                    'created_at,updated_at) VALUES '
                    + ','.join(values)
                    + ';'
                )
    else:
        rows = sql(
            "SELECT id,email FROM users "
            "WHERE email REGEXP '^perf[0-9]{4}@example[.]test$' "
            "ORDER BY email;"
        )
        ids = [
            (int(row.split('\t')[0]), row.split('\t')[1])
            for row in rows.splitlines()
        ]
    accounts, public = [], []
    for uid, email in ids:
        body, _ = request('POST', '/api/auth/login', {'email': email, 'password': PASSWORD}, csrf())
        token = body['data']['accessToken']
        claims = json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '==='))
        total = int(sql(f'SELECT COUNT(*) FROM orders WHERE user_id={uid};'))
        if total < 10:
            raise RuntimeError('회원별 주문 데이터 부족')
        pages = {}
        for page in {0, total // 20, (total - 1) // 10}:
            rows = sql(f'SELECT id FROM orders WHERE user_id={uid} ORDER BY created_at DESC,id DESC LIMIT 10 OFFSET {page*10};')
            pages[str(page)] = [int(x) for x in rows.splitlines()]
        accounts.append({'token': token, 'expiresAt': claims['exp'] * 1000,
            'userId': uid, 'total': total, 'pages': pages})
        public.append({'userId': uid, 'total': total, 'pages': pages})
    local = ROOT / '.local'
    local.mkdir(exist_ok=True)
    target = local / 'accounts.json'
    target.write_text(json.dumps(accounts))
    target.chmod(0o600)
    (local / 'fixture-public.json').write_text(json.dumps(public, indent=2))
    print(f'준비 완료: 회원 {len(accounts)}명 / 주문 {sum(a["total"] for a in accounts)}건. 토큰은 출력하지 않습니다.')

if __name__ == '__main__':
    main()
