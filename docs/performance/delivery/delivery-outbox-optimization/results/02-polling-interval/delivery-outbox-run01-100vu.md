# Outbox polling 100ms 100VU 결과

### 1. 테스트 목적

Outbox polling 간격을 줄였을 때 backlog 회복 속도와 배송 생성 성능이 어떻게 변하는지 확인한다.

### 2. 변경 내용

스케줄러 fixed delay를 `1,000ms`에서 `100ms`로 변경했다. 발행 대상 partial index와 batch 크기 100건은 유지했다.

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU / 8분 |
| 입력 | supplier 1개, receiver 18개, sleep 0초 |
| 비교 조건 | polling 간격 외 동일 |

### 4. 실행 결과

| 항목 | 1,000ms | 100ms | 변화 |
| --- | ---: | ---: | ---: |
| 배송 TPS | 106.01 | 74.02 req/s | -30.2% |
| p95 | 1.56 | 2.20초 | +41.0% |
| 테스트 중 Published TPS | 2.56 | 16.56건/s | 6.47배 |
| 회복 Published TPS | 79.39 | 183.23건/s | +130.8% |
| backlog 안정 0 | 630 | 194초 | -69.2% |

요청 35,528건은 모두 성공했고 배송·Outbox 증가량도 일치했다.

### 5. 모니터링 및 해석

| 항목 | 평균 / 최대 |
| --- | ---: |
| Domain B system CPU | 94.17% / 99.92% |
| Data VM CPU | 51.12% / 76.78% |
| Hikari pending | 10.24 / 41 |
| Outbox scheduler p95 | 1.12초 |

Outbox는 빨리 회복했지만 같은 프로세스의 배송 요청과 CPU·connection을 경쟁해 배송 TPS가 낮아졌다.

### 6. 결론

**WARN** — polling 단축만으로 backlog 회복은 빨라졌지만 foreground 처리량이 감소했다. 한 번의 polling 작업 시간을 줄이는 개선이 추가로 필요하다.
