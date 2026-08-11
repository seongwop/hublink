# 배송 성능 테스트

배송 생성 경로의 배정 동시성 제어와 Outbox 발행 과정을 단계별로 측정한 기록이다. 이전 결과에서 확인한 병목을 한 가지씩 변경했으며, 각 비교에서는 변경 변수 외 조건을 고정했다.

## 테스트 범위

- 대상 API: `POST /internal/deliveries`
- 기본 부하: 100VU, 1분 상승·5분 유지·2분 하강
- 용량 측정: 30초 상승 후 3분 고정 RPS
- 입력: supplier 1개 고정, receiver 18개 분산
- 확인 항목: k6, DB 정합성, Hikari, JVM, PostgreSQL, Outbox backlog, 로그

## 진행 순서

| 구분 | 단계 | 확인 내용 |
| --- | --- | --- |
| 배송 배정 | Redis 분산락 기준선 | 락 대기와 Hikari pool 포화 |
| 배송 배정 | 집계 테이블·bulk upsert | 반복 집계 쿼리와 쓰기 횟수 감소 |
| 배송 배정 | DB 비관적 락 | Redis timeout 제거와 DB 행 경합 확인 |
| 배송 배정 | 원자적 선점 | `SKIP LOCKED` 단일 SQL 적용 |
| 배송 배정 | 담당자 캐시 | User Service 반복 조회 제거 |
| 배송 배정 | 인프라·용량 검증 | DB CPU와 서비스 VM 경합 확인 |
| Outbox | 발행 대상 인덱스 | 완료 행 전체 검사 제거 |
| Outbox | polling 단축 | backlog 회복과 배송 처리량 간 영향 확인 |
| Outbox | Kafka 병렬 발행 | ACK 대기 중첩과 broker 요청 감소 |
| Outbox | 상태 배치 갱신 | 건별 UPDATE를 일괄 UPDATE로 변경 |
| Outbox | 최종 검증 | 동일 고정 RPS에서 전후 처리량 비교 |

## 문서

- [배송 배정 최적화](delivery-assignment-optimization/README.md)
- [Outbox 최적화](delivery-outbox-optimization/README.md)
- [성능 테스트 방법](../performance-test-plan.md)
- [Grafana 비교 대시보드](../../../monitoring/grafana/provisioning/dashboards/README.md)

반복 실행, 중단 결과, 원본 CSV와 로그는 `docs/performance/delivery/.local-archive/`에 보관한다. Git에는 단계별 대표 결과와 재현에 필요한 설정만 포함한다.
