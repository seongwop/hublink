/*
 * Gateway 부록 부하 테스트
 * - 대상: Gateway의 PATHS 목록, 기본값 /actuator/health와 /api/v1/orders
 * - 기본 부하: 1분 동안 20 VU까지 증가, 3분 동안 20 VU 유지, 1분 동안 0 VU로 감소
 * - 요청 간격: 각 VU가 Gateway 요청 1회 전송 후 SLEEP_SECONDS 만큼 대기, 기본값 1초
 * - 부하 증가: STAGES target 증가 또는 SLEEP_SECONDS 감소로 Gateway 요청량 증가
 * - 부하 감소: STAGES target 감소 또는 SLEEP_SECONDS 증가로 Gateway 요청량 감소
 * - 경로 분산: PATHS를 쉼표로 여러 개 지정해 route별 요청 분산
 * - 관찰 지표: Gateway p95/p99 latency, 5xx 비율, route별 지연, downstream span
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, sleepSeconds } from './lib/common.js';

// 부하 옵션 설정
// 배송 테스트 이후 Gateway 공통 진입 구간 확인
export const options = {
  stages: JSON.parse(__ENV.STAGES || JSON.stringify([
    { duration: '1m', target: 20 },
    { duration: '3m', target: 20 },
    { duration: '1m', target: 0 },
  ])),
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1500', 'p(99)<3000'],
    checks: ['rate>0.98'],
  },
};

// 대상 경로 목록
// PATHS 환경변수로 route별 부하 대상 조정
const paths = (__ENV.PATHS || '/actuator/health,/api/v1/orders')
  .split(',')
  .map((path) => path.trim())
  .filter(Boolean);

export default function () {
  // 경로 순환 선택
  // VU와 반복 번호 기준으로 요청 분산
  const path = paths[(__VU + __ITER) % paths.length];

  // Gateway 요청 실행
  // downstream 세부 병목은 Zipkin span으로 별도 확인
  const response = http.get(`${BASE_URL}${path}`, {
    tags: {
      name: 'gateway-appendix',
      target: path,
    },
  });

  // 응답 검증
  // 부록 테스트이므로 5xx 방어 여부 중심 확인
  check(response, {
    'status < 500': (res) => res.status < 500,
  });

  // 반복 간격 제어
  sleep(sleepSeconds());
}
