# 배송 담당자 캐시 통제 재현

## 목적

후속 User Service 조회 병목을 동일한 Caffeine Cache로 통제하고 배송 배정 동시성 제어 방식만 비교한다.

## 공통 조건

- 담당자 캐시 코드: `0bcc78b268ba1b000316ab05ce6049d229ab9d5f`
- 캐시 TTL: 3,600초
- 최대 캐시 Hub: 32개
- 테스트 전 동일 Hub 조회 워밍업
- 테스트 조건: 100VU, 8분
- 단계별 2회 이상 측정
- 대표 결과 선정 기준: 실패율 1% 미만, 성공 처리량과 p95 재현

## 재현 단계

| 단계 | 원본 SHA | 캐시 오버레이 |
|---|---|---|
| A-cache | `3c68e7d849c163d7ff895c8b56d6b40b0d3f5986` | 적용 |
| B-cache | `4a7663202857272091422c00f3012144f481eb62` | 적용 |
| C-cache | `86c4636f3f9dece7d4ad84e50e9b1e3ea037b83d` | 적용 |
| D-cache | `e0149eeffd3931ce4f2b843986950c9853716617` | 원본 포함 |

## 배포 입력

GitHub Actions의 `GCP CI/CD`를 수동 실행한다.

```text
deploy_scope: delivery-replay
delivery_source_ref: 단계별 원본 SHA
delivery_manager_cache_overlay: A/B/C는 true, D는 false
delivery_manager_cache_ttl_seconds: 3600
delivery_outbox_fixed_delay_ms: 1000
```

캐시 오버레이 이미지는 `<원본 SHA>-manager-cache` 태그로 생성한다. 기존 과거 이미지 태그는 덮어쓰지 않는다.

## 비교 해석

- A-cache → B-cache: 집계·벌크 처리와 Redis 락 범위 축소 효과
- B-cache → C-cache: Redis 분산락을 DB 비관적 락으로 전환한 효과
- C-cache → D-cache: 단순 `FOR UPDATE`를 원자적 선점과 `SKIP LOCKED`로 전환한 효과
- 기존 C 무캐시 결과: 락 제거 후 User Service 병목이 드러난 진단 결과
