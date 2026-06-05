import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, sleepSeconds } from './lib/common.js';

// Gateway 다중 경로 부하
export const options = {
  // ramp-up, 유지, ramp-down 단계
  stages: JSON.parse(__ENV.STAGES || JSON.stringify([
    { duration: '1m', target: 30 },
    { duration: '3m', target: 30 },
    { duration: '1m', target: 0 },
  ])),
  // Gateway 기준 최소 품질선
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2500'],
    checks: ['rate>0.99'],
  },
};

// 쉼표 기준 요청 경로 목록
const paths = (__ENV.PATHS || '/actuator/health,/v3/api-docs')
  .split(',')
  .map((path) => path.trim())
  .filter(Boolean);

export default function () {
  // 경로 랜덤 분산
  const path = paths[Math.floor(Math.random() * paths.length)];
  const response = http.get(`${BASE_URL}${path}`);

  // 정상 응답 검증
  check(response, {
    'status 2xx': (res) => res.status >= 200 && res.status < 300,
  });

  // VU 반복 간격
  sleep(sleepSeconds());
}
