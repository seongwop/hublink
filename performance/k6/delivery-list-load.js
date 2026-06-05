import http from 'k6/http';
import { check, sleep } from 'k6';
import { authHeaders, BASE_URL, sleepSeconds } from './lib/common.js';

// 배송 조회 API 읽기 부하
export const options = {
  // 배송 서비스 read 처리량 확인 단계
  stages: JSON.parse(__ENV.STAGES || JSON.stringify([
    { duration: '1m', target: 20 },
    { duration: '3m', target: 20 },
    { duration: '1m', target: 0 },
  ])),
  // 조회 API 기준 최소 품질선
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
    checks: ['rate>0.98'],
  },
};

// 페이지 크기 조절값
const pageSize = __ENV.PAGE_SIZE || '20';

export default function () {
  // 배송 목록 조회 요청
  const response = http.get(`${BASE_URL}/api/v1/deliveries?page=0&size=${pageSize}`, {
    headers: authHeaders({
      // 관리자 조회 기본 권한
      'X-User-Role': __ENV.USER_ROLE || 'MASTER',
    }),
  });

  // 조회 성공 검증
  check(response, {
    'status 200': (res) => res.status === 200,
  });

  // VU 반복 간격
  sleep(sleepSeconds());
}
