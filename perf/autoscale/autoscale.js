import http from 'k6/http';
import { check } from 'k6';

// 운영 api 오토스케일링 확인용: 공개 GET 엔드포인트에 일정한 요청률로 부하를 건다.
// 확장(6~7분)이 끝난 뒤의 응답 시간까지 보도록 기본 25분 동안 유지한다.
const base = __ENV.BASE || 'https://api.xevbn.click';
const rate = Number(__ENV.RATE || 600);
const duration = __ENV.DURATION || '25m';

export const options = {
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration,
            preAllocatedVUs: 300,
            maxVUs: Number(__ENV.MAX_VUS || 1500),
        },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    const res = http.get(`${base}/api/attractions`, { timeout: '10s' });
    check(res, { 'status 200': (r) => r.status === 200 });
}
