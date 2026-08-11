# DB 비관적 락 100VU 결과

### 1. 테스트 목적

Redis 분산락을 제거하고 배정 집계 행을 `FOR UPDATE`로 잠갔을 때 timeout과 처리량 변화를 확인한다.

### 2. 변경 내용

담당자 배정의 동시성 제어를 Redis에서 PostgreSQL 집계 행 비관적 락으로 이동했다.

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 상승, 5분 유지, 2분 하강 |
| 입력 | supplier 1개, receiver 18개, sleep 0초 |
| DB pool | Hikari 최대 30 |

### 4. 실행 결과

| 항목 | Redis 분산락 | DB 비관적 락 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 TPS | 20.05 | 28.79 req/s | +43.6% |
| 실패율 | 0.31% | 0% | -0.31%p |
| 평균 응답 | 4.07 | 2.83초 | -30.5% |
| p95 | 5.54 | 3.71초 | -33.0% |
| lock timeout | 30 | 0건 | 제거 |

DB 비관적 락 실행의 성공 요청 13,819건이 배송·경로·Outbox 증가량과 일치했다.

### 5. 모니터링 및 해석

| 항목 | 값 |
| --- | ---: |
| Hikari pending 최대 | 82 |
| Redis lock timeout | 0건 |
| HTTP 409 / 500 | 0 / 0건 |

Redis timeout은 제거됐지만 Hub 집계 행의 `FOR UPDATE` 대기가 길어지면서 p95 3초 기준은 넘었다. 병목이 Redis 대기에서 DB 행 락과 connection 대기로 이동한 결과다.

### 6. 결론

**WARN** — 오류와 Redis timeout은 제거했지만 긴 비관적 락 구간이 다음 개선 대상으로 남았다.
