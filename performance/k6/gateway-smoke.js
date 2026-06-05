import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, sleepSeconds } from './lib/common.js';

// Gateway 단일 경로 연결 확인
export const options = {
  vus: Number(__ENV.VUS || '10'),
  duration: __ENV.DURATION || '30s',
  // smoke 기준 최소 품질선
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2500'],
    checks: ['rate>0.99'],
  },
};

// 기본 health check 대상
const targetPath = __ENV.TARGET_PATH || '/actuator/health';

export default function () {
  // Gateway HTTP 요청
  const response = http.get(`${BASE_URL}${targetPath}`);

  // 정상 응답 검증
  check(response, {
    'status 2xx': (res) => res.status >= 200 && res.status < 300,
  });

  // VU 반복 간격
  sleep(sleepSeconds());
}
