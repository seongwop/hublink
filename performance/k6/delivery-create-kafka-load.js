/*
 * 배송 생성 Kafka 유입 테스트
 * - 대상: Gateway 주문 생성 API POST /api/v1/orders
 * - 기본 부하: 1분 동안 10 VU까지 증가, 3분 동안 10 VU 유지, 1분 동안 0 VU로 감소
 * - 요청 간격: 각 VU가 주문 생성 요청 1회 전송 후 SLEEP_SECONDS 만큼 대기, 기본값 1초
 * - 부하 증가: STAGES target 증가 또는 SLEEP_SECONDS 감소로 delivery.create Kafka 이벤트 유입량 증가
 * - 부하 감소: STAGES target 감소 또는 SLEEP_SECONDS 증가로 Kafka 이벤트 유입량 감소
 * - 데이터 분산: SUPPLIER_COMPANY_IDS, RECEIVER_COMPANY_IDS, PRODUCT_IDS를 쉼표로 여러 개 지정
 * - 관찰 지표: Kafka consumer lag, Delivery Create TPS, lag 회복 시간, delivery-service CPU/DB connection
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  BASE_URL,
  authHeaders,
  envList,
  pick,
  requiredEnv,
  sleepSeconds,
  uuidv4,
} from './lib/common.js';

// 부하 옵션 설정
// 주문 API를 통해 delivery.create Kafka 이벤트 유입량 생성
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

// 필수 seed 목록 로딩
// 단일 값과 쉼표 구분 다중 값 모두 지원
function requiredList(names) {
  for (const name of names) {
    const values = envList(name);
    if (values.length > 0) {
      return values;
    }
  }
  throw new Error(`${names.join(' 또는 ')} 환경변수 필요`);
}

const supplierCompanyIds = requiredList(['SUPPLIER_COMPANY_IDS', 'SUPPLIER_COMPANY_ID']);
const receiverCompanyIds = requiredList(['RECEIVER_COMPANY_IDS', 'RECEIVER_COMPANY_ID']);
const productIds = requiredList(['PRODUCT_IDS', 'PRODUCT_ID']);
const userId = requiredEnv('USER_ID');

// LocalDateTime 문자열 변환
// Java LocalDateTime 역직렬화 형식 맞춤
function pad(value) {
  return String(value).padStart(2, '0');
}

function toLocalDateTime(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function futureDeadline() {
  const date = new Date(Date.now() + Number(__ENV.DEADLINE_HOURS || '48') * 60 * 60 * 1000);
  return toLocalDateTime(date);
}

// 주문 생성 payload 조립
// 매 반복마다 orderKey를 바꿔 주문 멱등키 충돌 방지
function buildPayload(orderKey) {
  return {
    supplierCompanyId: pick(supplierCompanyIds),
    receiverCompanyId: pick(receiverCompanyIds),
    requestMemo: `k6 delivery kafka ${orderKey}`,
    requestedDeliveryDeadline: futureDeadline(),
    items: [
      {
        productId: pick(productIds),
        quantity: Number(__ENV.ORDER_QUANTITY || '1'),
      },
    ],
  };
}

export default function () {
  // 요청 식별자 생성
  // 주문 서비스 멱등성 확인용 X-Order-Key 사용
  const orderKey = uuidv4();

  // 주문 API 호출
  // 이후 재고 성공 흐름을 거쳐 delivery.create 이벤트 생성
  const response = http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify(buildPayload(orderKey)), {
    headers: authHeaders({
      'X-User-Id': userId,
      'X-Order-Key': orderKey,
    }),
    tags: {
      name: 'delivery-create-kafka',
      target: 'delivery.create',
    },
  });

  // 응답 검증
  // 비동기 배송 생성 성공 여부는 Kafka lag와 delivery row로 별도 확인
  check(response, {
    'order accepted': (res) => [200, 201, 202].includes(res.status),
  });

  // 실패 응답 로그
  // k6 summary만으로 원인 파악이 어려운 응답 본문 기록
  if (![200, 201, 202].includes(response.status)) {
    console.error(`status=${response.status}, body=${response.body}`);
  }

  // 반복 간격 제어
  sleep(sleepSeconds());
}
