"""개인 환경 전용 앱 기동. 실제 서비스 환경변수를 상속하지 않는다."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import time
import urllib.request

p = Path(__file__).resolve().parent
repo = p.parent.parent

# JAVA_HOME을 우선 사용하고, 없으면 PATH에서 Java를 찾는다.
java_home = os.environ.get('JAVA_HOME')
java_bin = (
    str(Path(java_home) / 'bin' / 'java')
    if java_home
    else shutil.which('java')
)

if not java_bin or not Path(java_bin).is_file():
    raise RuntimeError(
        'Java를 찾을 수 없습니다. JAVA_HOME 또는 PATH에 Java 17을 설정하세요.'
    )

# 다른 Java 버전으로 측정 조건이 바뀌는 것을 방지한다.
version_result = subprocess.run(
    [java_bin, '-version'],
    capture_output=True,
    text=True,
    check=True,
)
version_text = version_result.stdout + version_result.stderr

if not re.search(r'\bversion\s+"17(?:[."])', version_text):
    raise RuntimeError(
        '이 테스트는 Java 17을 사용합니다. JAVA_HOME 또는 PATH를 확인하세요.'
    )

jar = next((repo / 'build/libs').glob('*.jar'))
try:
    urllib.request.urlopen('http://127.0.0.1:18086/api/auth/csrf', timeout=2)
    raise RuntimeError('18086 포트에서 이미 앱이 응답합니다. 중복 실행하지 않습니다.')
except urllib.error.URLError:
    pass
(p / '.local').mkdir(parents=True, exist_ok=True)
log = (p / '.local/app.log').open('w')
child = subprocess.Popen([
    java_bin,
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
