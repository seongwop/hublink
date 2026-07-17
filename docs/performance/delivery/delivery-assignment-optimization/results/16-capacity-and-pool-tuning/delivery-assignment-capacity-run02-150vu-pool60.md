# Delivery Assignment Capacity Run 02 - 150VU Pool 60 결과

## 1. 테스트 목적

delivery-service Hikari maximum pool size를 30에서 60으로 늘리고 Run 01과 같은 150VU 부하를 반복해 처리량, 응답 지연, DB 포화가 개선되는지 확인한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트 시간 | 2026-07-17 00:29:26 ~ 00:37:31 KST |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| 실행 명령어 | `ENV_FILE=/tmp/hublink-k6-150vu-pool60.env STAGES='[{"duration":"1m","target":150},{"duration":"5m","target":150},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js` |
| VU / duration | 최대 150 VU / 8분 |
| 부하 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 조건 | supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| delivery Hikari pool | maximum 60 |
| 배포 이미지 digest | `sha256:0ada2fadc449...` |
| k6 로그 | `/tmp/hublink-k6-150vu-pool60-run01-20260717.log` |

본 테스트 전 VM이 `latest` 태그를 다시 pull하지 않아 이전 이미지 `sha256:d5ec05de100...`로 실행된 사실을 확인했다. 이전 이미지의 선점 메트릭도 현재 구현의 `reserve_atomic_skip_locked`가 아니라 `read_for_update_skip_locked`였다. 해당 예비 실행은 약 1분 만에 중단했고, 최신 이미지를 명시적으로 pull한 뒤 delivery-service health와 Eureka 등록, Hikari maximum 60, 원자적 선점 메트릭을 확인했다.

최신 이미지에서 20VU 20초 스모크를 먼저 실행해 549건 전부 성공, 실패율 0%, p95 923ms를 확인했다. 이후 baseline SQL을 다시 실행하고 본 테스트를 시작했으므로 중단 실행과 스모크 데이터는 공식 결과에 포함되지 않는다.

## 3. k6 실행 결과

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 36,146 |
| HTTP TPS | 75.30 req/s |
| 성공 요청 수 | 36,146 |
| 실패 요청 수 | 0 |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 1.62s / 1.50s |
| p90 / p95 / p99 | 2.58s / 3.06s / 4.16s |
| 최대 응답 시간 | 8.04s |
| max VU | 150 |

기능 실패와 HTTP 실패는 없었다. p99와 실패율 threshold는 통과했지만 p95가 목표 3초를 60ms 초과했다.

```text
checks: 100.00% > 90%
http_req_failed: 0.00% < 10%
p95: 3.06s > 3s
p99: 4.16s < 6s
```

## 4. DB / Outbox 결과

| 항목 | baseline | 테스트 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 69,746 | 36,146 |
| `p_delivery_route_histories` | 67,200 | 139,492 | 72,292 |
| `p_delivery_outboxes` | 33,600 | 69,746 | 36,146 |

k6 성공 수와 배송 및 Outbox 증가량이 정확히 일치하고, 경로 이력은 요청당 2건씩 증가했다. 테스트 종료 3분 11초 뒤 신규 Outbox 중 26,541건이 `PENDING`이었고 마지막 Outbox는 00:46:32 KST에 발행됐다. 전체 회복 시간은 종료 후 약 9분 1초다.

| 담당자 유형 | 집계 row | 최종 배정 합계 | 최소 | 최대 | 30건 도달 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `HUB_DELIVERY` | 1,500 | 37,946 | 25 | 27 | 0 |
| `COMPANY_DELIVERY` | 1,800 | 39,746 | 21 | 26 | 0 |

담당자 최대 배정은 27건으로 30건 한도에 도달하지 않았고, Hikari connection timeout도 0건이었다.

## 5. 캐시 및 외부 호출 결과

| 지표 | 값 |
| --- | ---: |
| cache hit | 약 73,817 |
| cache miss / put | 약 23 / 23 |
| cache size 최대 | 3 Hub |
| User 담당자 검색 실제 호출 | 약 23 |
| User 담당자 검색 오류 | 0 |
| Hub circuit failed / not permitted | 0 / 0 |
| User circuit failed / not permitted | 0 / 0 |

Prometheus `increase` 보간값을 반올림한 수치다. 60초 TTL과 3개 Hub 캐시 패턴이 유지됐으며 Pool 30 실행에서 발생한 Hub circuit 연쇄 실패는 재발하지 않았다.

## 6. Grafana 관찰 결과

| 지표 | Pool 30 | Pool 60 | 변화 |
| --- | ---: | ---: | ---: |
| Delivery API 1분 RPS 최대 | 93.57 | 91.67 | 2.03% 감소 |
| delivery-service CPU 최대 | 67.89% | 80.93% | 13.04%p 증가 |
| delivery-service heap 최대 | 361.81 MiB | 491.19 MiB | 35.76% 증가 |
| delivery-service GC pause 최대 | 206ms | 180ms | 12.62% 감소 |
| Hikari active 최대 | 30 / 30 | 60 / 60 | 30개 증가 |
| Hikari pending 최대 | 122 | 92 | 24.59% 감소 |
| Hikari pending 평균 | 80.12 | 65.75 | 17.94% 감소 |
| Tomcat busy ratio 최대 | 75.50% | 75.50% | 동일 |
| Data VM CPU 최대 | 99.98% | 100.00% | 동일한 포화 |
| Data VM CPU 평균 | 77.02% | 90.10% | 13.08%p 증가 |
| PostgreSQL connection 최대 | 60 | 84 | 40.00% 증가 |
| PostgreSQL commit TPS 최대 | 518.53 | 428.73 | 17.32% 감소 |
| Company 원자적 선점 1분 평균 최대 | 306.42ms | 261.77ms | 14.57% 감소 |
| Hub 원자적 선점 1분 평균 최대 | 204.32ms | 401.91ms | 96.71% 증가 |

Pool 확대는 connection 대기를 줄였지만 없애지는 못했다. active는 60개를 모두 사용했고 pending도 최대 92까지 증가했다. PostgreSQL connection은 84개까지 늘었으며 Data VM CPU 평균은 90.10%, 최대는 100%였다.

PostgreSQL `max_connections`는 100이고 superuser reserved connection은 3이다. 전체 서비스의 Hikari maximum 설정 합계는 111이므로 Pool 60은 모든 서비스가 동시에 증가할 때 운영 연결 예산을 초과한다. 실제 테스트 최대 84개도 일반 연결 가능 수 97개 중 13개만 남긴 상태라 운영 안전 여유가 부족하다.

heap 최대 사용량은 늘었지만 GC pause는 오히려 감소했고 Tomcat busy ratio도 같았다. 현재 결과만으로 JVM heap이나 GC가 주 병목이라고 볼 근거는 없다. Pool 60이 더 많은 DB 작업을 동시에 밀어 넣으면서 Data VM 포화와 Hub 담당자 선점 경합이 커진 것이 다음 한계에 가깝다.

### Data VM CPU 세부 분석

| 지표 | 값 |
| --- | ---: |
| CPU user 평균 | 77.09% |
| CPU system 평균 | 6.75% |
| CPU softirq 평균 | 4.94% |
| CPU iowait 평균 | 0.93% |
| CPU idle 평균 | 9.90% |
| load1 최대 | 56.70 |
| disk busy 최대 | 16.76% |
| disk write 최대 | 16.05 MiB/s |

iowait와 disk busy보다 user CPU가 높고 2 vCPU VM의 load1이 56.70까지 증가했다. 디스크 대기보다 실행 가능한 작업이 CPU를 기다린 연산 포화로 해석한다.

원자적 선점 SQL을 rollback 조건의 `EXPLAIN ANALYZE`로 확인한 결과 Hub 후보 1,500행과 Company 후보 1,800행을 각각 읽고 `active_assignment_count` 기준 quicksort 후 1행을 선점했다. 진단용 SQL의 배열 생성 시간이 포함돼 실행 시간 절대값은 실제 요청과 직접 비교할 수 없지만, 요청마다 큰 후보 집합을 scan·sort하는 실행 계획은 동일하다. Pool 60에서 이 쿼리가 Hub와 Company 배정마다 동시에 실행된 것이 CPU 포화의 주요 DB 경로다.

## 7. Zipkin / Loki 분석

Zipkin의 종료 ramp-down 구간 최근 성공 trace 1,000개를 집계했다.

| span | 표본 수 | 평균 | p95 | 최대 |
| --- | ---: | ---: | ---: | ---: |
| delivery root | 1,000 | 186.48ms | 340.85ms | 489.92ms |
| Hub route client | 1,000 | 4.35ms | 10.88ms | 50.30ms |
| User hub manager client | 1,000 | 10.57ms | 25.92ms | 65.28ms |
| User delivery manager search | 0 | - | - | - |

캐시가 warm인 최근 trace에는 User 담당자 검색 span이 없었고 Hub 및 User 외부 호출도 수십 ms 이내였다. 다만 Zipkin API가 반환한 최신 1,000건은 ramp-down 후반의 낮은 부하 표본이므로 k6 전체 지연 분포를 대표하지 않는다. 전체 구간의 Hikari pending과 DB CPU를 우선 병목 근거로 사용한다.

테스트 구간에는 domain-b Promtail 컨테이너가 중지돼 Loki에 delivery-service 로그 스트림이 수집되지 않았다. 따라서 WARN/ERROR 0건으로 해석하지 않고 관측 공백으로 분류한다. HTTP 실패 0건은 k6 결과와 circuit, Hikari timeout 메트릭으로 교차 확인했다.

## 8. Pool 30 / 60 핵심 비교

| 지표 | 150VU Pool 30 | 150VU Pool 60 | 변화 |
| --- | ---: | ---: | ---: |
| 총 요청 수 | 34,742 | 36,146 | 4.04% 증가 |
| 총 TPS | 72.38 | 75.30 | 4.03% 증가 |
| 성공 처리량 | 65.33 | 75.30 | 15.26% 증가 |
| 실패율 | 9.73% | 0.00% | 9.73%p 감소 |
| p95 | 4.51s | 3.06s | 32.15% 감소 |
| p99 | 6.23s | 4.16s | 33.23% 감소 |
| 최대 응답 시간 | 11.64s | 8.04s | 30.93% 감소 |
| Hikari pending 최대 | 122 | 92 | 24.59% 감소 |
| Data VM CPU 평균 | 77.02% | 90.10% | 13.08%p 증가 |
| PostgreSQL connection 최대 | 60 | 84 | 40.00% 증가 |
| Outbox 회복 | 8분 | 9분 1초 | 1분 1초 증가 |

Pool 60은 실패를 제거하고 성공 처리량을 15.26% 높였으며 p95와 p99도 약 32~33% 줄였다. 그러나 총 TPS 증가는 4.03%에 그쳤고 p95 3초 목표를 통과하지 못했다. 동시에 DB connection과 Data VM 평균 CPU가 크게 늘고 Outbox 회복도 느려졌다.

## 9. 결론

```text
WARN - Pool 60으로 성공 처리량과 안정성은 개선됐지만 Data VM 포화는 악화

- 총 36,146건 전부 성공, 실패율 0%
- 성공 처리량 65.33 -> 75.30 req/s, 15.26% 증가
- p95 4.51초 -> 3.06초, 32.15% 감소
- Hikari pending 최대 122 -> 92
- PostgreSQL connection 최대 60 -> 84
- Data VM CPU 평균 77.02% -> 90.10%, 최대 100%
- 담당자 한도 고갈과 JVM GC 병목 근거 없음
- Outbox 정합성 일치, 종료 후 9분 1초에 회복
```

Pool 60은 이번 150VU 조건에서 유효한 개선이지만 여유 용량을 만든 설정은 아니다. Pool을 더 늘리면 2 vCPU Data VM에 추가 부하만 줄 가능성이 높다. 다음 실험은 Pool 60을 유지한 채 Outbox publisher만 중지해 요청 트랜잭션의 Outbox INSERT는 유지하고 비동기 발행 부하를 분리한다. 이후 담당자 후보를 Hub와 유형으로 바로 좁히고 정렬을 줄일 수 있는 인덱스 또는 집계 테이블 구조를 별도 비교한다.
