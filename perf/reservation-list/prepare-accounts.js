import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { ensureAccount } from './auth.js';

// 회원가입·로그인은 부하 측정과 분리한 준비 작업이다. 실제 PasswordEncoder를 사용한다.
const completed = new Counter('accounts_prepared');
export const options = {
  scenarios: { prepare: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
  thresholds: { accounts_prepared: ['count==1'] },
};

export default function () {
  let ready = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    const response = http.get('http://app:8081/actuator/health', { timeout: '2s' });
    if (response.status === 200 && response.json().status === 'UP') {
      ready = true;
      break;
    }
    sleep(1);
  }
  if (!ready) throw new Error('앱 준비 시간 초과. Compose app 로그를 확인하세요.');
  for (let index = 0; index < 200; index++) {
    ensureAccount(index);
    if ((index + 1) % 25 === 0) console.log(`회원 준비: ${index + 1}/200`);
  }
  completed.add(1);
}
