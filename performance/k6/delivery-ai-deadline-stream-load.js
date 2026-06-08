/*
 * Redis Stream 기반 AI 마감 생성 처리량 테스트
 * - 대상: delivery-service 테스트 API POST /api/v1/deliveries/test/deadline-requested
 * - 기본 부하: 1분 동안 10 VU까지 증가, 3분 동안 10 VU 유지, 1분 동안 0 VU로 감소
 * - 요청 간격: 각 VU가 deadline requested 이벤트 주입 요청 1회 전송 후 SLEEP_SECONDS 만큼 대기, 기본값 1초
 * - 부하 증가: STAGES target 증가 또는 SLEEP_SECONDS 감소로 deadline:requested:stream XADD 요청량 증가
 * - 부하 감소: STAGES target 감소 또는 SLEEP_SECONDS 증가로 Redis Stream 이벤트 유입량 감소
 * - 데이터 조절: PRODUCT_NAMES, PRODUCT_QUANTITY, DEADLINE_DAYS, ROUTE_* 값으로 AI 마감 계산 payload 변경
 * - 관찰 지표: requested stream lag, pending entry, AI Consume TPS, generated stream 증가량
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  BASE_URL,
  authHeaders,
  envList,
  pick,
  sleepSeconds,
  uuidv4,
} from './lib/common.js';

// 부하 옵션 설정
// 배송 테스트 API로 deadline:requested:stream 이벤트 주입
export const options = {
  stages: JSON.parse(__ENV.STAGES || JSON.stringify([
    { duration: '1m', target: 10 },
    { duration: '3m', target: 10 },
    { duration: '1m', target: 0 },
  ])),
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2500'],
    checks: ['rate>0.99'],
  },
};

// 호출 대상 설정
// 협업자 스크립트와 분리된 배송 도메인 전용 Stream 주입 테스트
const TARGET_PATH = __ENV.TARGET_PATH || '/api/v1/deliveries/test/deadline-requested';
const productNames = envList('PRODUCT_NAMES', 'PRODUCT_NAME');

// LocalDateTime 문자열 변환
// requestedArrivalAt, orderedAt 형식 맞춤
function pad(value) {
  return String(value).padStart(2, '0');
}

function toLocalDateTime(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function plusDays(days) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return toLocalDateTime(date);
}

// DeadlineRequestedEvent payload 조립
// 실제 배송 생성 이후 발행되는 이벤트 구조 기준
function buildPayload() {
  const receiverUserId =
    __ENV.RECEIVER_USER_ID ||
    __ENV.USER_ID ||
    '44444444-4444-4444-4444-444444444444';

  return {
    eventId: uuidv4(),
    deliveryId: uuidv4(),
    orderId: uuidv4(),
    ordererName: `k6-orderer-${__VU}-${__ITER}`,
    ordererEmail: `k6-orderer-${__VU}-${__ITER}@hublink.test`,
    orderedAt: toLocalDateTime(new Date()),
    requestMessage: __ENV.REQUEST_MESSAGE || '마감 시간 계산 요청',
    receiverUserId,
    receiverSlackId: __ENV.RECEIVER_SLACK_ID || 'U1234567890',
    products: [
      {
        productName: pick(productNames) || 'k6-test-product',
        quantity: Number(__ENV.PRODUCT_QUANTITY || '1'),
      },
    ],
    requestedArrivalAt: __ENV.REQUESTED_ARRIVAL_AT || plusDays(Number(__ENV.DEADLINE_DAYS || '3')),
    departureHubName: __ENV.DEPARTURE_HUB_NAME || '수도권 허브',
    destinationAddress: __ENV.DESTINATION_ADDRESS || '서울특별시 강남구 테헤란로 1',
    deliveryManagerName: __ENV.DELIVERY_MANAGER_NAME || 'k6-manager',
    deliveryManagerEmail: __ENV.DELIVERY_MANAGER_EMAIL || 'k6-manager@hublink.test',
    routeInfo: [
      {
        hubRouteId: uuidv4(),
        sequence: 1,
        departureHubId: uuidv4(),
        departureHubName: __ENV.DEPARTURE_HUB_NAME || '수도권 허브',
        arrivalHubId: uuidv4(),
        arrivalHubName: __ENV.ARRIVAL_HUB_NAME || '영남권 허브',
        arrivalCompanyId: uuidv4(),
        arrivalCompanyName: __ENV.ARRIVAL_COMPANY_NAME || 'k6-company',
        estimatedDistanceKm: Number(__ENV.ESTIMATED_DISTANCE_KM || '150.5'),
        estimatedDurationMin: Number(__ENV.ESTIMATED_DURATION_MIN || '120'),
        routeType: __ENV.ROUTE_TYPE || 'HUB_TO_HUB',
      },
    ],
    workStartTime: __ENV.WORK_START_TIME || '09:00',
    workEndTime: __ENV.WORK_END_TIME || '18:00',
  };
}

export default function () {
  // Stream 주입 API 호출
  // AI consume 완료 여부는 Redis lag와 generated stream 증가량으로 별도 확인
  const response = http.post(`${BASE_URL}${TARGET_PATH}`, JSON.stringify(buildPayload()), {
    headers: authHeaders({
      'X-User-Role': __ENV.USER_ROLE || 'MASTER',
    }),
    tags: {
      name: 'delivery-ai-deadline-stream',
      target: 'deadline-requested-stream',
    },
  });

  // 응답 검증
  // HTTP 성공은 Stream 주입 성공까지만 의미
  check(response, {
    'stream event accepted': (res) => res.status >= 200 && res.status < 300,
    'response time < 1000ms': (res) => res.timings.duration < 1000,
  });

  // 실패 응답 로그
  // Redis XADD 실패와 validation 실패 원인 확인
  if (response.status < 200 || response.status >= 300) {
    console.error(`status=${response.status}, body=${response.body}`);
  }

  // 반복 간격 제어
  sleep(sleepSeconds());
}
