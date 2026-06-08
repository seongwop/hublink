/*
 * 배송 생성 Kafka 이벤트 주입 테스트
 * - 대상: delivery-service 테스트 API POST /api/v1/deliveries/test/delivery-create
 * - 기본 부하: 1분 동안 10 VU까지 증가, 3분 동안 10 VU 유지, 1분 동안 0 VU로 감소
 * - 요청 간격: 각 VU가 delivery.create 이벤트 주입 요청 1회 전송 후 SLEEP_SECONDS 만큼 대기, 기본값 1초
 * - 부하 증가: STAGES target 증가 또는 SLEEP_SECONDS 감소로 delivery.create Kafka 이벤트 유입량 증가
 * - 부하 감소: STAGES target 감소 또는 SLEEP_SECONDS 증가로 Kafka 이벤트 유입량 감소
 * - 병목 분리: Gateway, order-service, stock-service를 우회하고 delivery-service Kafka consumer 처리량 관찰
 * - 관찰 지표: Kafka consumer lag, Delivery Create TPS, lag 회복 시간, delivery-service CPU/DB connection
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  authHeaders,
  envList,
  pick,
  sleepSeconds,
  uuidv4,
} from './lib/common.js';

// 부하 옵션 설정
// delivery.create topic 유입량과 consumer lag 관찰
export const options = {
  stages: JSON.parse(__ENV.STAGES || JSON.stringify([
    { duration: '1m', target: 10 },
    { duration: '3m', target: 10 },
    { duration: '1m', target: 0 },
  ])),
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2500', 'p(99)<5000'],
    checks: ['rate>0.95'],
  },
};

// 호출 대상 설정
// Gateway rate limit과 주문/재고 흐름을 제외하기 위해 delivery-service 직접 호출
const DELIVERY_BASE_URL = (__ENV.DELIVERY_BASE_URL || 'http://10.10.0.30:19099').replace(/\/$/, '');
const TARGET_PATH = __ENV.TARGET_PATH || '/api/v1/deliveries/test/delivery-create';

// 필수 seed 목록 로딩
// 단일 값과 쉼표 구분 다중 값 모두 지원
function requiredList(names) {
  for (const name of names) {
    const values = envList(name);
    if (values.length > 0) {
      return values;
    }
  }
  throw new Error(`${names.join(' 또는 ')} 환경변수가 필요`);
}

const supplyCompanyIds = requiredList([
  'SUPPLY_COMPANY_IDS',
  'SUPPLY_COMPANY_ID',
  'SUPPLIER_COMPANY_IDS',
  'SUPPLIER_COMPANY_ID',
]);
const receiverCompanyIds = requiredList(['RECEIVER_COMPANY_IDS', 'RECEIVER_COMPANY_ID']);
const productNames = envList('PRODUCT_NAMES', 'PRODUCT_NAME');

// LocalDateTime 문자열 변환
// Java LocalDateTime 역직렬화 형식 맞춤
function pad(value) {
  return String(value).padStart(2, '0');
}

function toLocalDateTime(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function plusHours(hours) {
  return toLocalDateTime(new Date(Date.now() + hours * 60 * 60 * 1000));
}

// DeliveryRequest payload 조립
// 매 요청마다 orderId를 바꿔 consumer 멱등성 충돌 방지
function buildPayload() {
  const orderId = uuidv4();
  const productName = pick(productNames) || 'k6-test-product';

  return {
    orderId,
    ordererName: __ENV.ORDERER_NAME || `k6-orderer-${__VU}`,
    ordererEmail: __ENV.ORDERER_EMAIL || `k6-orderer-${__VU}@hublink.test`,
    orderedAt: toLocalDateTime(new Date()),
    requestMessage: __ENV.REQUEST_MESSAGE || `k6 delivery kafka ${orderId}`,
    products: [
      {
        productName,
        quantity: Number(__ENV.PRODUCT_QUANTITY || __ENV.ORDER_QUANTITY || '1'),
      },
    ],
    supplyCompanyId: pick(supplyCompanyIds),
    receiverCompanyId: pick(receiverCompanyIds),
    deliveryAddress: __ENV.DELIVERY_ADDRESS || '서울특별시 강남구 테헤란로 1',
    receiverName: __ENV.RECEIVER_NAME || 'k6-receiver',
    requestedArrivalAt: __ENV.REQUESTED_ARRIVAL_AT || plusHours(Number(__ENV.DEADLINE_HOURS || '48')),
  };
}

export default function () {
  // delivery.create 이벤트 주입 API 호출
  // HTTP 성공은 Kafka broker publish 성공 기준
  const response = http.post(`${DELIVERY_BASE_URL}${TARGET_PATH}`, JSON.stringify(buildPayload()), {
    headers: authHeaders({
      'X-User-Role': __ENV.USER_ROLE || 'MASTER',
    }),
    tags: {
      name: 'delivery-create-kafka',
      target: 'delivery-create-test-api',
    },
  });

  // 응답 검증
  // 실제 배송 생성 성공 여부는 Kafka lag와 delivery row 생성량으로 별도 확인
  check(response, {
    'delivery.create event published': (res) => [200, 201, 202].includes(res.status),
  });

  // 실패 응답 로그
  // Kafka publish timeout 또는 payload 검증 실패 원인 확인
  if (![200, 201, 202].includes(response.status)) {
    console.error(`status=${response.status}, body=${response.body}`);
  }

  // 반복 간격 제어
  sleep(sleepSeconds());
}
