# 배송 인프라 용량 조정 100VU 결과

### 1. 테스트 목적

DB pool 증설 후에도 처리량이 늘지 않은 원인을 확인하고 DB CPU와 공유 VM 경합을 조정한다.

### 2. 변경 내용

- Hikari pool 30에서 60으로 증설 후 효과 확인
- Data VM 2 vCPU에서 4 vCPU로 확장
- 배송 서비스를 Domain B 공유 VM에서 전용 VM으로 분리

### 3. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 애플리케이션 | 동일 `bfb1d50` 이미지 |
| 부하 | 최대 100VU / 8분 |
| 입력 | supplier 1개, receiver 18개, sleep 0초 |
| 개선 전 | DB 2 vCPU + 공유 VM |
| 개선 후 | DB 4 vCPU + 배송 전용 VM |

### 4. 실행 결과

| 항목 | 개선 전 | 개선 후 | 변화 |
| --- | ---: | ---: | ---: |
| 성공 TPS | 76.19 | 172.18 req/s | +126.0% |
| p95 | 1.98초 | 805.87ms | -59.3% |
| 실패율 | 0% | 0% | 동일 |
| 고정 100 RPS 완료 처리량 | 57.75 | 92.84 req/s | +60.8% |
| dropped iteration | 6,928 | 0건 | 제거 |

### 5. 모니터링 및 해석

| 항목 | 개선 전 평균 | 개선 후 평균 |
| --- | ---: | ---: |
| Delivery system CPU | 88.76% | 49.10% |
| Data VM CPU | 82.94% | 93.91% |
| Hikari pending | 21.21 | 27.46 |

pool만 30에서 60으로 늘린 150VU 실험은 처리량 증가가 약 4%에 그쳤고 DB CPU는 100%에 도달했다. connection 수보다 DB 연산 자원이 먼저 포화된 결과다.

### 6. 결론

**PASS** — 동일 이미지 재검증에서 처리량과 지연이 개선됐다. DB 스케일업과 VM 분리를 함께 적용한 누적 결과이므로 두 변경의 단독 효과로 표현하지 않는다.

[100VU 저장 시계열](../../../../../../monitoring/grafana/provisioning/dashboards/comparison-100vu/03-infrastructure-capacity-comparison.json)
