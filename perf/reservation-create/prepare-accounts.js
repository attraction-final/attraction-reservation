import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE, csrfHeaders, login } from '/shared/auth.js';

const runId = __ENV.RUN_ID;
if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,11}$/.test(runId || '')) throw new Error('잘못된 RUN_ID');
const users = Number(__ENV.TEST_VUS || 50);
if (![50,100,200].includes(users)) throw new Error('TEST_VUS는 50, 100, 200이어야 합니다.');
const prepared = new Counter('accounts_prepared');
export const options = {
  scenarios: { prepare: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  thresholds: { accounts_prepared: ['count==1'] },
};

export default function () {
  let ready = false;
  for (let i = 0; i < 90; i++) {
    const response = http.get('http://app:8081/actuator/health', { timeout: '2s' });
    if (response.status === 200 && response.json().status === 'UP') { ready = true; break; }
    sleep(1);
  }
  if (!ready) throw new Error('앱 준비 실패. 전용 app 로그를 확인하세요.');
  for (let i = 0; i < users; i++) {
    const suffix = String(i).padStart(2, '0');
    const email = `kan150-${runId}-${suffix}@example.test`;
    const response = http.post(`${BASE}/api/users/signup`, JSON.stringify({
      email, password: 'PerfLocal123!', nickname: `k150-${runId}-${suffix}`,
    }), { headers: csrfHeaders(), timeout: '15s', responseCallback: http.expectedStatuses(201, 409) });
    if (![201, 409].includes(response.status)) throw new Error(`회원 준비 실패: HTTP ${response.status}`);
    login(email);
  }
  prepared.add(1);
}
