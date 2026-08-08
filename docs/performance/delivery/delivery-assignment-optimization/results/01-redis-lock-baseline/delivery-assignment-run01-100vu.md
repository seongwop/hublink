# Redis 분산락 기준선 100VU 결과

### 1. 테스트 목적

Redis 분산락으로 배송 담당자를 배정하던 초기 구조의 100VU 처리 한계를 확인한다.

### 2. 변경 내용

변경 전 기준선이다. 회사와 Hub 단위 락이 담당자 조회부터 배송 저장까지의 구간을 감싼다.

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 상승, 5분 유지, 2분 하강 |
| 입력 | supplier 1개, receiver 18개, sleep 0초 |
| lock wait | 2초 |

### 4. 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 | 6,915건 |
| 성공 / 실패 | 6,910 / 5건 |
| 성공 TPS | 14.40 req/s |
| 평균 응답 | 4.67초 |
| p95 / p99 | 6.06초 / 6.55초 |
| lock timeout | 5건 |

### 5. 모니터링 및 해석

| 항목 | 값 |
| --- | ---: |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 83 |
| Delivery system CPU 최대 | 64.52% |
| JVM heap 최대 | 약 435.5MiB |

80VU에서 100VU로 올려도 TPS는 `14.28 → 14.40`에 머물렀고 응답시간만 증가했다. DB pool 대기와 Redis 락 대기가 함께 누적된 구간이다.

### 6. 결론

**WARN** — 요청 대부분은 처리됐지만 p95·p99 기준을 넘었고 lock timeout 5건이 발생했다. 이후 비교의 기준선으로 사용한다.
