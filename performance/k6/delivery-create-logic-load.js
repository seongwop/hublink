/*
 * 배송 생성 DB/락 병목 테스트
 * - 대상: delivery-service 내부 배송 생성 API POST /internal/deliveries
 * - 부하 모델: LOAD_MODEL=vu 또는 LOAD_MODEL=rps 선택
 * - 기본 부하: 1분 동안 5 VU까지 증가, 3분 동안 5 VU 유지, 1분 동안 0 VU로 감소
 * - 요청 간격: 각 VU가 배송 생성 요청 1회 전송 후 SLEEP_SECONDS 만큼 대기, 기본값 1초
 * - 부하 증가: STAGES target 증가 또는 SLEEP_SECONDS 감소로 동시 배송 생성 요청 증가
 * - 부하 감소: STAGES target 감소 또는 SLEEP_SECONDS 증가로 동시 배송 생성 요청 감소
 * - 락 경합 강화: RECEIVER_COMPANY_ID 단일 값 사용으로 같은 도착 회사 기준 lock 경합 유도
 * - 락 경합 분산: RECEIVER_COMPANY_IDS를 쉼표로 여러 개 지정
 * - 관찰 지표: Hikari active/pending, DB wait event, lock wait/timeout, p95/p99 latency
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

const loadModel = (__ENV.LOAD_MODEL || 'vu').toLowerCase();
const rpsLoadModel = loadModel === 'rps';

function positiveInteger(name, defaultValue) {
  const value = Number(__ENV[name] || defaultValue);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name}은 양의 정수여야 함`);
  }
  return value;
}

function loadOptions() {
  const thresholds = {
    http_req_failed: ['rate<0.10'],
    http_req_duration: ['p(95)<3000', 'p(99)<6000'],
    checks: ['rate>0.90'],
  };

  if (rpsLoadModel) {
    const targetRps = positiveInteger('TARGET_RPS', 40);
    const preAllocatedVUs = positiveInteger('PRE_ALLOCATED_VUS', 180);
    const maxVUs = positiveInteger('MAX_VUS', 250);
    if (maxVUs < preAllocatedVUs) {
      throw new Error('MAX_VUS는 PRE_ALLOCATED_VUS 이상이어야 함');
    }

    thresholds.dropped_iterations = ['count==0'];
    return {
      scenarios: {
        delivery_create_rps: {
          executor: 'ramping-arrival-rate',
          startRate: 0,
          timeUnit: '1s',
          preAllocatedVUs,
          maxVUs,
          stages: JSON.parse(__ENV.RPS_STAGES || JSON.stringify([
            { duration: '30s', target: targetRps },
            { duration: '3m', target: targetRps },
          ])),
          gracefulStop: __ENV.GRACEFUL_STOP || '30s',
        },
      },
      thresholds,
    };
  }

  if (loadModel !== 'vu' && loadModel !== 'vus') {
    throw new Error(`지원하지 않는 LOAD_MODEL: ${loadModel}`);
  }

  return {
    stages: JSON.parse(__ENV.STAGES || JSON.stringify([
      { duration: '1m', target: 5 },
      { duration: '3m', target: 5 },
      { duration: '1m', target: 0 },
    ])),
    thresholds,
  };
}

// VU와 고정 RPS 부하 모델 선택
export const options = loadOptions();

// 호출 대상 설정
// Gateway에 internal route가 없어서 delivery-service 직접 호출
const DELIVERY_BASE_URL = (__ENV.DELIVERY_BASE_URL || 'http://10.10.0.70:19099').replace(/\/$/, '');
const TARGET_PATH = __ENV.TARGET_PATH || '/internal/deliveries';

// 필수 seed 목록 로딩
// 같은 receiverCompanyId 사용 시 담당자 배정 락 경합 강화
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
// 배송 요청 시간 필드 형식 맞춤
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
// 매 요청마다 orderId를 바꿔 중복 배송 제약 충돌 방지
function buildPayload() {
  const orderId = uuidv4();
  const productName = pick(productNames) || 'k6-test-product';

  return {
    orderId,
    ordererName: __ENV.ORDERER_NAME || `k6-orderer-${__VU}`,
    ordererEmail: __ENV.ORDERER_EMAIL || `k6-orderer-${__VU}@hublink.test`,
    orderedAt: toLocalDateTime(new Date()),
    requestMessage: __ENV.REQUEST_MESSAGE || `k6 delivery create ${orderId}`,
    products: [
      {
        productName,
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
  // 배송 내부 API 호출
  // Kafka consumer를 우회해 생성 로직 자체의 병목 분리
  const response = http.post(`${DELIVERY_BASE_URL}${TARGET_PATH}`, JSON.stringify(buildPayload()), {
    headers: authHeaders({
      'X-User-Role': __ENV.USER_ROLE || 'MASTER',
    }),
    tags: {
      name: 'delivery-create-logic',
      target: 'internal-delivery-create',
    },
  });

  // 응답 검증
  // DB/락 병목은 p95/p99와 Hikari pending으로 함께 확인
  check(response, {
    'delivery create accepted': (res) => [200, 201, 202].includes(res.status),
  });

  // 실패 응답 로그
  // NO_DELIVERY_MANAGER, LOCK_TIMEOUT 등 원인 분리용
  if (![200, 201, 202].includes(response.status)) {
    console.error(`status=${response.status}, body=${response.body}`);
  }

  // VU 부하 반복 간격 제어
  if (!rpsLoadModel) {
    sleep(sleepSeconds());
  }
}
