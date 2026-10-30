"""k6 버전 고정, 실행 조건/원본 결과/로컬 자원 샘플을 함께 보관한다."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import threading
from prepare import sql

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
IMAGE = 'grafana/k6@sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e'
DIGEST_QUERY = "SELECT DIGEST_TEXT,COUNT_STAR,SUM_ROWS_EXAMINED,SUM_ROWS_SENT,SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='attraction_perf' AND DIGEST_TEXT LIKE 'SELECT%' ORDER BY SUM_TIMER_WAIT DESC LIMIT 40;"

def output(cmd):
    return subprocess.check_output(cmd, text=True, cwd=REPO).strip()

def main():
    p = argparse.ArgumentParser()
    p.add_argument('run_id')
    p.add_argument('--mode', choices=['smoke', 'load'], default='smoke')
    p.add_argument('--vus', type=int, default=2)
    p.add_argument('--duration', choices=['30s','60s','90s'], default='60s')
    args = p.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.run_id):
        raise ValueError('실행 ID는 영문·숫자·밑줄·하이픈만 사용')
    folder = ROOT / 'results' / args.run_id
    folder.mkdir(exist_ok=False)
    subprocess.run([sys.executable, str(ROOT / 'prepare.py')], check=True)
    metadata = {'runId': args.run_id, 'startedAt': datetime.now(timezone.utc).isoformat(),
        'commit': output(['git','rev-parse','HEAD']),
        'workingChanges': output(['git','status','--short']),
        'mode': args.mode, 'vus': args.vus, 'duration': args.duration,
        'thinkSeconds': 1, 'pageMix': 'first/middle/last cyclic, size=10',
        'model': 'constant-vus closed model' if args.mode == 'load' else 'per-vu-iterations',
        'java': '17; Xms256m Xmx512m', 'hikariMaxPoolSize': 10,
        'host': {'system': platform.system(), 'machine': platform.machine(), 'cpuCount': os.cpu_count()},
        'docker': output(['docker','version','--format','{{.Client.Version}} / {{.Server.Version}}']),
        'k6': output(['docker','run','--rm',IMAGE,'version']),
        'dockerResources': output(['docker','info','--format','CPU={{.NCPU}} memoryBytes={{.MemTotal}}']),
        'limitations': ['부하 발생기·앱·DB가 같은 Mac의 자원을 공유함',
            '조회 전용 합성 주문: 항목·결제·티켓 없음, 업무 생성 검증용 아님',
            '새 스키마와 로컬 더미 비밀값. 공용 배포 환경의 수용 능력으로 일반화 불가',
            '소규모 준비 검증이며 트래픽 목표·성능 합격 기준은 아직 확정하지 않음'],
    }
    (folder / 'metadata.json').write_text(json.dumps(metadata, indent=2, ensure_ascii=False))
    (folder / 'fixture.json').write_text((ROOT / '.local/fixture-public.json').read_text())
    (folder / 'sql-digests-before.txt').write_text(sql(DIGEST_QUERY, admin=True))
    for name, query in {
        'db-before.txt': "SELECT VERSION(); SELECT COUNT(*) FROM orders; SELECT user_id,COUNT(*) FROM orders GROUP BY user_id; SHOW INDEX FROM orders; SHOW CREATE TABLE orders;",
        'plans-before.txt': "EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id=(SELECT MIN(id) FROM users) ORDER BY created_at DESC,id DESC LIMIT 10; EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id=(SELECT MIN(id) FROM users) ORDER BY created_at DESC,id DESC LIMIT 10 OFFSET 500; EXPLAIN ANALYZE SELECT COUNT(*) FROM orders WHERE user_id=(SELECT MIN(id) FROM users);",
    }.items():
        (folder / name).write_text(sql(query))
    stop = threading.Event()
    def sample():
        with (folder / 'resource-samples.jsonl').open('w') as log:
            while not stop.is_set():
                snapshot = {'at': datetime.now(timezone.utc).isoformat()}
                try:
                    snapshot['containers'] = output(['docker','stats','--no-stream','--format','{{json .}}',
                        'attraction-order-perf-mysql-1','attraction-order-perf-redis-1'])
                    pid = (ROOT / '.local/app.pid').read_text().strip()
                    snapshot['appProcess'] = output(['ps','-p',pid,'-o','pid=,%cpu=,rss='])
                except subprocess.CalledProcessError as e:
                    snapshot['samplingError'] = str(e)
                log.write(json.dumps(snapshot) + '\n'); log.flush()
                stop.wait(2)
    worker = threading.Thread(target=sample, daemon=True)
    worker.start()
    command = ['docker','run','--rm','--user',f'{os.getuid()}:{os.getgid()}',
        '-v',f'{ROOT}:/scripts:ro','-v',f'{folder}:/results',
        '-e',f'MODE={args.mode}','-e',f'VUS={args.vus}','-e',f'DURATION={args.duration}',
        '-e','K6_WEB_DASHBOARD=true','-e','K6_WEB_DASHBOARD_EXPORT=/results/dashboard.html',
        IMAGE,'run','--out','json=/results/metrics.json','/scripts/order-list.js']
    try:
        with (folder / 'terminal.txt').open('w') as log:
            child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            for line in child.stdout:
                print(line, end=''); log.write(line)
            code = child.wait()
    finally:
        stop.set(); worker.join(timeout=10)
    metadata['finishedAt'] = datetime.now(timezone.utc).isoformat()
    metadata['exitCode'] = code
    (folder / 'metadata.json').write_text(json.dumps(metadata, indent=2, ensure_ascii=False))
    (folder / 'sql-digests-after.txt').write_text(sql(DIGEST_QUERY, admin=True))
    print('결과 폴더:', folder)
    raise SystemExit(code)

if __name__ == '__main__':
    main()
