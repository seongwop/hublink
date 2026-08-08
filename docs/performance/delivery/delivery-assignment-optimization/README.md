# 배송 담당자 배정 최적화

## 초기 구조

회사와 Hub 단위 Redis 분산락 안에서 담당자 조회, 현재 배정 수 집계, 배송 저장을 처리했다. 동일 Hub 요청이 늘면 락 대기와 DB connection 대기가 함께 증가했다.

## 변경 단계

| 단계 | 변경 | 대표 결과 |
| --- | --- | --- |
| [01. Redis 락 기준선](results/01-redis-lock-baseline/delivery-assignment-run01-100vu.md) | 전체 임계 구간 Redis 락 | 14.40 req/s, p95 6.06초 |
| [02. 집계·bulk upsert](results/02-aggregate-bulk-upsert/delivery-assignment-run01-100vu.md) | 실시간 이력 집계를 별도 테이블로 분리 | 22.29 req/s, p95 4.76초 |
| [03. DB 비관적 락](results/03-db-pessimistic-lock/delivery-assignment-run01-100vu.md) | Redis 락을 집계 행 `FOR UPDATE`로 전환 | 28.79 req/s, lock timeout 0건 |
| [04. 원자적 선점](results/04-atomic-reservation/delivery-assignment-run01-100vu.md) | 후보 선택과 배정 수 증가를 단일 SQL로 처리 | 172.33 req/s, p95 778.23ms |
| [05. 담당자 캐시](results/05-manager-cache/delivery-assignment-run01-100vu.md) | Hub별 담당자 목록 Caffeine 캐시 | User 조회 4,144회에서 24회로 감소 |
| [06. 인프라 용량](results/06-infrastructure-capacity/delivery-assignment-run01-100vu.md) | DB vCPU 확장과 배송 VM 분리 | 76.19에서 172.18 req/s로 증가 |
| [07. 고정 RPS 용량](results/07-fixed-rps-capacity/delivery-assignment-capacity-summary.md) | 100~170 RPS 단계 측정 | 권장 지속 부하 150 RPS 이하 |

## 구현 위치

- 원자적 선점 SQL: [`DeliveryAssignmentCountRepositoryImpl`](../../../../delivery-service/src/main/java/com/msa/delivery_service/repository/DeliveryAssignmentCountRepositoryImpl.java)
- 배정 집계 처리: [`DeliveryAssignmentCountService`](../../../../delivery-service/src/main/java/com/msa/delivery_service/service/DeliveryAssignmentCountService.java)
- 담당자 캐시: [`DeliveryManagerCacheConfig`](../../../../delivery-service/src/main/java/com/msa/delivery_service/config/DeliveryManagerCacheConfig.java)
- 서비스 설정: [`delivery-service.yml`](../../../../config-repo/delivery-service.yml)

담당자 캐시는 `expireAfterWrite` 60초와 최대 32개 Hub를 사용한다. 다중 인스턴스에서 담당자 변경을 즉시 반영해야 하는 경우에는 이벤트 기반 무효화가 추가로 필요하다.
