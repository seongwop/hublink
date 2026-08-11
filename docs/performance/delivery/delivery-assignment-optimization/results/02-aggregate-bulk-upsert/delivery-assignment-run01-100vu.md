# 배정 집계·bulk upsert 100VU 결과

### 1. 테스트 목적

배송 이력 전체를 매번 집계하던 방식을 별도 집계 테이블과 bulk upsert로 변경한 효과를 확인한다.

### 2. 변경 내용

- 담당자별 활성 배송 수를 `p_delivery_assignment_counts`에 저장
- 반복 upsert를 한 번의 bulk upsert로 처리
- Redis 분산락 유지

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 상승, 5분 유지, 2분 하강 |
| 입력 | supplier 1개, receiver 18개, sleep 0초 |
| lock wait | 2초 |

### 4. 실행 결과

| 항목 | Redis 락 기준선 | 집계·bulk upsert | 변화 |
| --- | ---: | ---: | ---: |
| 성공 TPS | 14.40 | 22.29 req/s | +54.8% |
| 실패 | 5 | 0건 | 제거 |
| 평균 응답 | 4.67 | 3.66초 | -21.6% |
| p95 | 6.06 | 4.76초 | -21.5% |
| p99 | 6.55 | 8.48초 | +29.5% |

성공 요청 10,701건과 배송·Outbox 증가량이 일치했고 failed·DLQ Outbox 증가는 없었다.

### 5. 모니터링 및 해석

| 항목 | 값 |
| --- | ---: |
| Hikari active 최대 | 10 |
| Hikari pending 최대 | 92 |
| Data VM CPU 최대 | 50.42% |
| GC pause 최대 | 45ms |

쓰기 횟수 감소로 처리량은 늘었지만 80VU 이후에는 약 22 req/s에서 증가가 멈췄다. p99와 Hikari pending은 오히려 증가해 락과 connection 점유가 남은 병목으로 확인됐다.

### 6. 결론

**WARN** — 집계 구조 변경으로 처리량과 p95는 개선됐지만 100VU 지연 기준은 통과하지 못했다.
