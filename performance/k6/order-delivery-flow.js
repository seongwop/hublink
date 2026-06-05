import http from 'k6/http';
import { check, sleep } from 'k6';
import { authHeaders, BASE_URL, requiredEnv, sleepSeconds, uuidv4 } from './lib/common.js';

// 주문 생성 기반 배송 비동기 흐름 부하
export const options = {
  // Kafka 발행과 배송 consumer 처리량 확인 단계
  stages: JSON.parse(__ENV.STAGES || JSON.stringify([
    { duration: '1m', target: 10 },
    { duration: '3m', target: 10 },
    { duration: '1m', target: 0 },
  ])),
  // 비동기 흐름 기준 최소 품질선
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2500', 'p(99)<5000'],
    checks: ['rate>0.95'],
  },
};

// 주문 생성 필수 seed
const supplierCompanyId = requiredEnv('SUPPLIER_COMPANY_ID');
const receiverCompanyId = requiredEnv('RECEIVER_COMPANY_ID');
const productId = requiredEnv('PRODUCT_ID');
const userId = requiredEnv('USER_ID');

// 요청 배송 마감 시간
function futureDeadline() {
  const date = new Date(Date.now() + Number(__ENV.DEADLINE_HOURS || '48') * 60 * 60 * 1000);
  return date.toISOString().slice(0, 19);
}

export default function () {
  // 주문 멱등키
  const orderKey = uuidv4();
  // 주문 생성 payload
  const payload = {
    supplierCompanyId,
    receiverCompanyId,
    requestMemo: `k6 delivery flow ${orderKey}`,
    requestedDeliveryDeadline: futureDeadline(),
    items: [
      {
        productId,
        quantity: Number(__ENV.ORDER_QUANTITY || '1'),
      },
    ],
  };

  // 주문 생성 요청
  const response = http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify(payload), {
    headers: authHeaders({
      'X-User-Id': userId,
      'X-Order-Key': orderKey,
    }),
  });

  // 동기 요청 접수 검증
  check(response, {
    'order accepted': (res) => [200, 201, 202].includes(res.status),
  });

  // VU 반복 간격
  sleep(sleepSeconds());
}
