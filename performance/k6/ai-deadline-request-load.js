import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, authHeaders, uuidv4, sleepSeconds } from './lib/common.js';

// 테스트 대상 API 경로
const TARGET_PATH =
    __ENV.TARGET_PATH || '/api/v1/deliveries/test/deadline-requested';

// 부하 단계 설정
const STAGES = __ENV.STAGES
    ? JSON.parse(__ENV.STAGES)
    : [
        { duration: '10s', target: 1 },
        { duration: '10s', target: 0 },
    ];

// k6 실행 옵션 - 실패율, 응답 시간, check 성공률 기준을 설정한다.
export const options = {
    stages: STAGES,
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<1000', 'p(99)<2500'],
        checks: ['rate>0.99'],
    },
};

// Java LocalDateTime에서 받을 수 있도록 yyyy-MM-ddTHH:mm:ss 형식으로 변환
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

// 요청 Body 생성
// 매 요청마다 새로운 DeadlineRequestedEvent 형태의 payload를 만든다.
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
        requestMessage:
            __ENV.REQUEST_MESSAGE || '12월 12일 3시까지는 보내주세요!',

        receiverUserId,
        receiverSlackId: __ENV.RECEIVER_SLACK_ID || 'U1234567890',

        products: [
            {
                productName: __ENV.PRODUCT_NAME || '마른 오징어',
                quantity: Number(__ENV.PRODUCT_QUANTITY || 50),
            },
        ],

        requestedArrivalAt: __ENV.REQUESTED_ARRIVAL_AT || plusDays(3),

        departureHubName: __ENV.DEPARTURE_HUB_NAME || '경기 북부 센터',
        destinationAddress:
            __ENV.DESTINATION_ADDRESS ||
            '부산시 사하구 낙동대로 1번길 1 해산물월드',

        deliveryManagerName: __ENV.DELIVERY_MANAGER_NAME || '고길동',
        deliveryManagerEmail:
            __ENV.DELIVERY_MANAGER_EMAIL || 'kdk@sparta.world',

        routeInfo: [
            {
                hubRouteId: uuidv4(),
                sequence: 1,

                departureHubId: uuidv4(),
                departureHubName: __ENV.DEPARTURE_HUB_NAME || '경기 북부 센터',

                arrivalHubId: uuidv4(),
                arrivalHubName: __ENV.ARRIVAL_HUB_NAME || '대전광역시 센터',

                arrivalCompanyId: uuidv4(),
                arrivalCompanyName: __ENV.ARRIVAL_COMPANY_NAME || 'OO업체',

                estimatedDistanceKm: Number(__ENV.ESTIMATED_DISTANCE_KM || 150.5),
                estimatedDurationMin: Number(__ENV.ESTIMATED_DURATION_MIN || 120),
                routeType: __ENV.ROUTE_TYPE || 'HUB_TO_HUB',
            },
        ],

        workStartTime: __ENV.WORK_START_TIME || '09:00',
        workEndTime: __ENV.WORK_END_TIME || '18:00',
    };
}

// k6가 VU마다 반복 실행하는 메인 함수
// delivery-service 테스트 API를 호출해 deadline:requested:stream에 이벤트 주입
export default function () {
    const url = `${BASE_URL}${TARGET_PATH}`;

    const response = http.post(url, JSON.stringify(buildPayload()), {
        headers: authHeaders({
            'X-User-Role': __ENV.USER_ROLE || 'MASTER',
        }),
        tags: {
            name: 'ai-deadline-request',
            target: 'deadline-requested-stream',
        },
    });

    // 응답 검증
    check(response, {
        'status is 2xx': (res) => res.status >= 200 && res.status < 300,
        'response time < 1000ms': (res) => res.timings.duration < 1000,
    });

    // 실패 응답일 경우 원인 확인을 위해 status와 body를 출력한다.
    if (response.status < 200 || response.status >= 300) {
        console.error(`status=${response.status}, body=${response.body}`);
    }

    // 요청 간 대기 시간
    sleep(sleepSeconds());
}