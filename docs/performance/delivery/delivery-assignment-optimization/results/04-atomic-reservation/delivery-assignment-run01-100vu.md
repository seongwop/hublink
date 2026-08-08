# 원자적 담당자 선점 100VU 결과

### 1. 테스트 목적

후보 선택과 배정 수 증가를 한 SQL로 합치고 잠긴 후보를 건너뛰도록 변경한 효과를 확인한다.

### 2. 변경 내용

- 후보 선택과 `active_assignment_count + 1`을 단일 SQL로 처리
- `FOR UPDATE SKIP LOCKED` 적용
- 전후 모두 담당자 캐시 TTL 60초 적용

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 상승, 5분 유지, 2분 하강 |
| 입력 | supplier 1개, receiver 18개, sleep 0초 |
| 워밍업 | 저부하 실행 후 DB 기준선 재초기화 |

### 4. 실행 결과

| 항목 | `FOR UPDATE` | 원자적 `SKIP LOCKED` | 변화 |
| --- | ---: | ---: | ---: |
| 성공 TPS | 36.03 | 172.33 req/s | +378.3% |
| 실패율 | 0% | 0% | 동일 |
| 평균 응답 | 2.26초 | 472.12ms | -79.1% |
| p95 | 3.33초 | 778.23ms | -76.6% |
| 성공 요청 | 17,293 | 82,720건 | +378.3% |

최종 실행의 배송·Outbox 증가량은 성공 요청 82,720건과 일치했고 경로 이력은 165,440건 증가했다.

### 5. 모니터링 및 해석

| 항목 | `FOR UPDATE` | 원자적 `SKIP LOCKED` |
| --- | ---: | ---: |
| Hikari pending 최대 | 42 | 42 |
| Data VM CPU 최대 | 37.16% | 99.96% |
| lock timeout / deadlock | 0 / 0 | 0 / 0 |

처리량이 약 4.8배 증가한 상태에서도 Hikari pending 최대값은 같았다. 최종 실행에서는 DB CPU가 새로운 상한으로 확인됐다.

### 6. 결론

**PASS** — 동일 캐시 조건에서 잠금 구간을 단일 SQL로 줄인 뒤 처리량과 응답시간이 함께 개선됐다.

[100VU 저장 시계열](../../../../../../monitoring/grafana/provisioning/dashboards/comparison-100vu/02-atomic-reservation-comparison.json)
