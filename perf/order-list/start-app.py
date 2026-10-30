"""개인 환경 전용 앱 기동. 실제 서비스 환경변수를 상속하지 않는다."""
from pathlib import Path
import subprocess
import time
import urllib.request

p = Path(__file__).resolve().parent
repo = p.parent.parent
jar = next((repo / 'build/libs').glob('*.jar'))
try:
    urllib.request.urlopen('http://127.0.0.1:18086/api/auth/csrf', timeout=2)
    raise RuntimeError('18086 포트에서 이미 앱이 응답합니다. 중복 실행하지 않습니다.')
except urllib.error.URLError:
    pass
log = (p / '.local/app.log').open('w')
child = subprocess.Popen([
    '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java',
    '-Xms256m', '-Xmx512m', '-jar', str(jar), '--spring.profiles.active=perf',
    '--spring.config.additional-location=file:' + str(p / 'application-perf.yaml'),
], cwd=repo, stdout=log, stderr=subprocess.STDOUT,
    env={'PATH': '/usr/bin:/bin:/usr/local/bin', 'LANG': 'en_US.UTF-8'}, start_new_session=True)
(p / '.local/app.pid').write_text(str(child.pid))
for _ in range(60):
    if child.poll() is not None:
        raise RuntimeError('기동 실패: .local/app.log 확인')
    try:
        with urllib.request.urlopen('http://127.0.0.1:18086/api/auth/csrf', timeout=1) as r:
            if r.status == 200:
                print(f'개인 테스트 앱 준비 완료: PID {child.pid}, 127.0.0.1:18086')
                break
    except urllib.error.URLError:
        time.sleep(0.5)
else:
    raise RuntimeError('기동 대기 시간 초과: .local/app.log 확인')
