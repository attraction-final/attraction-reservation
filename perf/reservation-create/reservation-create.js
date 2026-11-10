import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import execution from 'k6/execution';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { BASE, login } from '/shared/auth.js';

const runId = __ENV.RUN_ID;
if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,11}$/.test(runId || '')) throw new Error('잘못된 RUN_ID');
const scenario = __ENV.SCENARIO || 'sold-out';
if (!['sold-out','single','spread'].includes(scenario)) throw new Error('잘못된 SCENARIO');
const users = Number(__ENV.TEST_VUS || 50);
if (![50,100,200,500,1000].includes(users)) throw new Error('TEST_VUS는 50, 100, 200, 500, 1000이어야 합니다.');
const setupTimeoutSeconds = users >= 500 ? 120 : 60;
const expectedSuccess = scenario === 'sold-out' ? 20 : users;
const expectedRejection = users - expectedSuccess;
const expectedSlots = scenario === 'spread' ? 5 : 1;
const fixture = new SharedArray('create-fixture', () => open(`/results/${runId}/fixture.jsonl`).trim().split(/\r?\n/).map(row => JSON.parse(row)));
const sent = new Counter('reservation_create_requests');
const succeeded = new Counter('reservation_create_success');
const rejected = new Counter('reservation_create_expected_rejection');
const unexpected = new Counter('reservation_create_unexpected');
const successDuration = new Trend('reservation_create_success_duration', true);
const rejectionDuration = new Trend('reservation_create_rejection_duration', true);
const requestStartedAt = new Gauge('reservation_create_request_started_at_ms');
const setupStartedAt = new Gauge('reservation_create_setup_started_at_ms');
const setupFinishedAt = new Gauge('reservation_create_setup_finished_at_ms');
export const options = {
  setupTimeout: `${setupTimeoutSeconds}s`,
  scenarios: { [scenario]: { executor: 'per-vu-iterations', vus: users, iterations: 1, maxDuration: '90s', gracefulStop: '10s' } },
  summaryTrendStats: ['avg','med','max','p(95)','p(99)'],
  thresholds: {
    checks: ['rate==1'], reservation_create_requests: [`count==${users}`],
    reservation_create_success: [`count==${expectedSuccess}`], reservation_create_expected_rejection: [`count==${expectedRejection}`],
    reservation_create_unexpected: ['count==0'], 'http_req_failed{endpoint:reservation-create}': ['rate==0'],
  },
};

export function setup() {
  setupStartedAt.add(Date.now());
  if (fixture.length !== users || new Set(fixture.map(x => x.userId)).size !== users
      || new Set(fixture.map(x => x.ticketId)).size !== users || new Set(fixture.map(x => x.slotId)).size !== expectedSlots
      || fixture.some(x => x.scenario !== scenario || x.users !== users)) {
    throw new Error('설정한 회원·티켓 수와 시나리오별 슬롯 배정을 확인하세요.');
  }
  for (const slotId of new Set(fixture.map(x => x.slotId))) {
    if (fixture.filter(x => x.slotId === slotId).length !== users / expectedSlots) throw new Error('슬롯별 회원 배정 불일치');
  }
  // 실제 서버 시간을 바꾸지 않는다. 토큰은 setup 메모리에서만 전달한다.
  // setup 대기 + scenario 최대 90초 + gracefulStop 10초 + 여유 20초를 확보한다.
  const accounts = fixture.map(account => ({ ...account, token: login(account.email, setupTimeoutSeconds + 120) }));
  setupFinishedAt.add(Date.now());
  return accounts;
}

export default function (accounts) {
  const account = accounts[__VU - 1];
  // 공통 목표 시각에 요청을 모은다. 실제 동시성은 요청 시작 Gauge 범위로 확인한다.
  const remaining = execution.scenario.startTime + 1500 - Date.now();
  if (remaining > 0) sleep(remaining / 1000);
  requestStartedAt.add(Date.now());
  const response = http.post(`${BASE}/api/reservations`, JSON.stringify({ ticketId: account.ticketId, slotId: account.slotId }), {
    headers: { Authorization: `Bearer ${account.token}`, 'Content-Type': 'application/json' },
    timeout: '30s', tags: { endpoint: 'reservation-create' },
    responseCallback: scenario === 'sold-out' ? http.expectedStatuses(201,409) : http.expectedStatuses(201),
  });
  let body = null;
  try { body = response.json(); } catch (_) { /* 아래에서 예상하지 못한 응답으로 집계한다. */ }
  const ok = response.status === 201 && body?.success === true && body.data?.reservationId > 0
    && body.data.ticketId === account.ticketId && body.data.slotId === account.slotId && body.data.status === 'RESERVED';
  // 현재 공통 오류 응답에는 errorCode가 없어 실제 ErrorCode의 메시지를 확인한다.
  const expected = scenario === 'sold-out' && response.status === 409 && body?.success === false && [
    '예약 가능한 슬롯 상태가 아닙니다.', '슬롯이 마감되었거나 잔여 정원이 없어 예약할 수 없습니다.',
  ].includes(body.message);
  sent.add(1); succeeded.add(ok ? 1 : 0); rejected.add(expected ? 1 : 0); unexpected.add(ok || expected ? 0 : 1);
  if (ok) successDuration.add(response.timings.duration);
  if (expected) rejectionDuration.add(response.timings.duration);
  check(response, { '예약 성공 또는 정원 소진에 따른 업무 거절': () => ok || expected });
}

export function handleSummary(data) {
  // setup_data에는 토큰이 있으므로 허용한 집계 필드만 저장한다.
  const summary = { metrics: data.metrics, root_group: data.root_group, options: data.options, state: data.state };
  const count = key => data.metrics[key]?.values.count ?? 0;
  return {
    stdout: `요청=${count('reservation_create_requests')} 성공=${count('reservation_create_success')} 예상거절=${count('reservation_create_expected_rejection')} 예상외=${count('reservation_create_unexpected')}\n`,
    [`/results/${runId}/summary.json`]: JSON.stringify(summary, null, 2),
  };
}
