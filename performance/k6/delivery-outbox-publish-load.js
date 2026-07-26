/*
 * 배송 Outbox 발행 병목 테스트
 * - 대상: delivery-service 내부 배송 생성 API POST /internal/deliveries와 후속 outbox poller
 * - 기본 부하: 1분 동안 5 VU까지 증가, 3분 동안 5 VU 유지, 1분 동안 0 VU로 감소
 * - 요청 간격: 각 VU가 배송 생성 요청 1회 전송 후 SLEEP_SECONDS 만큼 대기, 기본값 1초
 * - 부하 증가: STAGES target 증가 또는 SLEEP_SECONDS 감소로 p_delivery_outboxes 적재량 증가
 * - 부하 감소: STAGES target 감소 또는 SLEEP_SECONDS 증가로 outbox 유입 속도 감소
 * - 병목 분리: RECEIVER_COMPANY_IDS를 여러 개 지정해 생성 lock 경합을 낮추고 outbox polling과 Kafka publish 지표 관찰
 * - 관찰 지표: Outbox PENDING 최대값, Outbox Publish TPS, Kafka send latency, succeed topic 발행량
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
// 배송 생성 성공 후 delivery outbox 발행 병목 확인
export const options = {
  stages: JSON.parse(__ENV.STAGES || JSON.stringify([
    { duration: '1m', target: 5 },
    { duration: '3m', target: 5 },
    { duration: '1m', target: 0 },
  ])),
  thresholds: {
    http_req_failed: ['rate<0.10'],
    http_req_duration: ['p(95)<3000', 'p(99)<6000'],
    checks: ['rate>0.90'],
  },
};

// 호출 대상 설정
// 배송 생성 성공 이벤트 outbox를 만들기 위해 내부 생성 API 사용
const DELIVERY_BASE_URL = (__ENV.DELIVERY_BASE_URL || 'http://10.10.0.70:19099').replace(/\/$/, '');
const TARGET_PATH = __ENV.TARGET_PATH || '/internal/deliveries';

// 필수 seed 목록 로딩
// 2번 생성 로직 테스트와 같은 seed 체계 사용
function requiredList(names) {
  for (const name of names) {
    const values = envList(name);
    if (values.length > 0) {
      return values;
    }
  }
  throw new Error(`${names.join(' 또는 ')} 환경변수 필요`);
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
// 배송 생성 요청 DTO 형식 맞춤
function pad(value) {
  return String(value).padStart(2, '0');
}

function toLocalDateTime(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function plusHours(hours) {
  return toLocalDateTime(new Date(Date.now() + hours * 60 * 60 * 1000));
}

// 배송 생성 payload 조립
// 성공 시 p_delivery_outboxes에 delivery.create.succeed 저장
function buildPayload() {
  const orderId = uuidv4();

  return {
    orderId,
    ordererName: __ENV.ORDERER_NAME || `k6-outbox-orderer-${__VU}`,
    ordererEmail: __ENV.ORDERER_EMAIL || `k6-outbox-${__VU}@hublink.test`,
    orderedAt: toLocalDateTime(new Date()),
    requestMessage: __ENV.REQUEST_MESSAGE || `k6 delivery outbox ${orderId}`,
    products: [
      {
        productName: pick(productNames) || 'k6-test-product',
        quantity: Number(__ENV.PRODUCT_QUANTITY || '1'),
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
  // 배송 생성 요청
  // k6 응답 시간과 outbox worker 처리 시간은 별도 기록
  const response = http.post(`${DELIVERY_BASE_URL}${TARGET_PATH}`, JSON.stringify(buildPayload()), {
    headers: authHeaders({
      'X-User-Role': __ENV.USER_ROLE || 'MASTER',
    }),
    tags: {
      name: 'delivery-outbox-publish',
      target: 'delivery-create-succeed-outbox',
    },
  });

  // 응답 검증
  // outbox 발행 성공 여부는 PENDING/PUBLISHED row와 Kafka topic으로 별도 확인
  check(response, {
    'delivery accepted': (res) => [200, 201, 202].includes(res.status),
  });

  // 실패 응답 로그
  // 배송 생성 실패와 outbox 발행 지연 분리
  if (![200, 201, 202].includes(response.status)) {
    console.error(`status=${response.status}, body=${response.body}`);
  }

  // 반복 간격 제어
  sleep(sleepSeconds());
}
