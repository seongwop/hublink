// Gateway 기본 주소
export const BASE_URL = (__ENV.BASE_URL || 'http://10.10.0.10:19091').replace(/\/$/, '');

// 공통 인증 헤더 조립
export function authHeaders(extra = {}) {
  const headers = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    ...extra,
  };

  // 선택 인증 토큰
  if (__ENV.ACCESS_TOKEN) {
    headers.Authorization = `Bearer ${__ENV.ACCESS_TOKEN}`;
  }
  // 서비스 공통 사용자 식별자
  if (__ENV.USER_ID) {
    headers['X-User-Id'] = __ENV.USER_ID;
  }
  // 권한별 API 테스트 헤더
  if (__ENV.USER_ROLE) {
    headers['X-User-Role'] = __ENV.USER_ROLE;
  }

  return headers;
}

// 주문 멱등키 생성용 UUID
export function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = Math.floor(Math.random() * 16);
    const value = char === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

// 필수 seed 환경변수 검증
export function requiredEnv(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} 환경변수 필요`);
  }
  return value;
}

// 요청 간 대기 시간
export function sleepSeconds() {
  return Number(__ENV.SLEEP_SECONDS || '1');
}
