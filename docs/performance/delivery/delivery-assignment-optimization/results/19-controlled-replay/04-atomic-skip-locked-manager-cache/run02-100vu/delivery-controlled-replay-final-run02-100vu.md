# 배송 배정 통제 재현 04 - 원자적 선점·SKIP LOCKED·담당자 캐시 100VU 재검증

## 1. 테스트 목적

최종 개선 이미지에서 발생한 직전 Run의 초기 Hub 회로 차단과 처리량 저하가 구현 성능인지, 콜드 상태의 일시적인 현상인지 구분한다. 짧은 워밍업과 검증을 통과한 뒤 같은 100VU 조건을 다시 실행하고, 과거 정상 대표 Run과 비교한다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 유효 트래픽 구간 | 2026-07-29 14:09:39 ~ 14:17:39 KST |
| Cloud Run 실행 | `hublink-k6-load-test-h2f5w` |
| 대상 API | `POST /internal/deliveries` |
| 부하 | 최대 100VU / 8분 |
| 패턴 | 1분 ramp-up, 5분 유지, 2분 ramp-down |
| 입력 | supplier 1개 고정, receiver 18개 분산, client sleep 0초 |
| Delivery 이미지 | `e0149eeffd3931ce4f2b843986950c9853716617` |
| 적용 내용 | DB 원자적 선점, `FOR UPDATE SKIP LOCKED`, 담당자 Caffeine 캐시 |
| 담당자 배정 한도 | 60건 |
| Hikari maximum pool size | 60 |
| Delivery VM | 전용 `e2-standard-2` |
| Data VM | `e2-standard-4`, PostgreSQL·Redis·Kafka 공유 |
| 초기 데이터 | 배송 33,600건, 경로 67,200건, Outbox 33,600건 |

본 테스트 전 서비스 재시작 없이 다음 순서로 워밍업했다.

1. 10VU 30초: 3,982건 성공, 실패 0건, p95 92.24ms
2. 5VU 20초 검증: 2,279건 성공, 실패 0건, p95 48.52ms
3. Outbox `PENDING/FAILED` 0, Redis Streams pending·lag 0 확인
4. 처리 완료된 두 Stream을 길이 0으로 정리
5. 본 실행 직전 기준선 SQL로 DB 초기화

## 3. 판정

**PASS - 워밍업된 정상 상태의 대표 결과**

- 82,720건이 모두 성공했고 실패율과 중단 iteration은 0%다.
- 처리량 `172.33 req/s`, 평균 `472.12ms`, p95 `778.23ms`다.
- 과거 정상 대표값과 비교해 처리량은 1.50% 낮고 평균과 p95는 각각 1.57%, 1.73% 높아 사실상 같은 수준으로 재현됐다.
- 직전 콜드 Run보다 성공 처리량은 52.80% 증가하고 p95는 32.33% 감소했다.
- Hub·User 회로 차단은 전 구간 `closed`, 5xx와 배정 lock timeout은 0건이다.
- Data VM CPU는 평균 93.21%, 최대 99.96%로 다시 포화에 접근했다. 현재 상한은 JVM 메모리보다 DB CPU와 connection 대기다.

## 4. k6 결과

| 항목 | 결과 |
| --- | ---: |
| 총 요청 | 82,720 |
| 성공 / 실패 | 82,720 / 0 |
| HTTP TPS | 172.33 req/s |
| 실패율 | 0.00% |
| checks 성공률 | 100.00% |
| 평균 / median | 472.12ms / 490.02ms |
| p90 / p95 | 709.35ms / 778.23ms |
| 최대 응답 시간 | 2.91s |
| 중단 iteration | 0 |
| 종료 코드 | 0 |

## 5. 과거 정상 대표값 및 직전 Run 비교

| 지표 | 과거 정상 대표 A | 직전 Run01 | 이번 Run02 | 대표 A 대비 |
| --- | ---: | ---: | ---: | ---: |
| 총 요청 | 83,981 | 59,506 | 82,720 | -1.50% |
| 성공 요청 | 83,981 | 54,140 | 82,720 | -1.50% |
| 실패 요청 | 0 | 5,366 | 0 | 동일 |
| 성공 TPS | 174.95 | 112.78 | 172.33 | -1.50% |
| 실패율 | 0.00% | 9.02% | 0.00% | 동일 |
| 평균 응답 | 464.83ms | 656.51ms | 472.12ms | +1.57% |
| p95 | 765ms | 1.15s | 778.23ms | +1.73% |
| 최대 응답 | 2.40s | 6.55s | 2.91s | +21.25% |
| Data VM CPU 평균 / 최대 | 90.01% / 99.89% | 59.15% / 77.81% | 93.21% / 99.96% | 같은 포화 |
| Hikari active 평균 / 최대 | 49.88 / 60 | 48.24 / 60 | 51.09 / 60 | 유사 |
| Hikari pending 평균 / 최대 | 25.76 / 41 | 10.42 / 33 | 24.73 / 42 | 유사 |

과거 정상 대표 A는 `18-db-vm-scale-up/delivery-assignment-db-vm-scale-up-run06-100vu-stability-validation.md`의 워밍업 후보 A다. 당시와 현재는 GCP 프로젝트와 서비스 배치가 달라 자원 수치는 참고 비교지만, k6 결과와 DB 포화 형태는 거의 동일하다.

직전 Run01은 워밍업 없이 시작해 초기 Hub 호출 5건이 지연되고 회로 차단이 열렸다. 이번 Run02에서 같은 이미지가 과거 대표값을 재현했으므로, Run01의 `112.78 req/s`는 최종 구현의 정상 처리량이 아니라 콜드 상태 민감도 결과로 분류한다.

## 6. 통제 재현 3단계 비교

| 지표 | Redis 분산락 2초 | 집계·벌크·락 범위 축소 | 최종 Run02 |
| --- | ---: | ---: | ---: |
| 성공 처리량 | 15.88 req/s | 17.34 req/s | 172.33 req/s |
| 실패율 | 45.90% | 43.35% | 0.00% |
| 평균 응답 | 2.77s | 2.66s | 472.12ms |
| p95 | 3.59s | 3.32s | 778.23ms |

최종 구현은 최초 단계보다 성공 처리량이 985.22% 증가하고 p95가 78.32% 감소했다. 중간 단계 대비 성공 처리량은 893.84% 증가하고 p95는 76.56% 감소했다.

## 7. 배정 선점과 담당자 캐시

| 계측 | 평균 | 최대 | 건수 |
| --- | ---: | ---: | ---: |
| Company 원자적 선점 | 102.98ms | 478.76ms | 82,720 |
| Hub 원자적 선점 | 125.82ms | 549.21ms | 82,720 |
| 담당자 캐시 hit | - | - | 165,416 |
| 담당자 캐시 miss | - | - | 24 |
| 담당자 캐시 hit ratio | 99.99% | - | 165,440 |

두 원자적 선점 횟수는 성공 요청과 각각 일치하며, 캐시 조회 총합은 성공 요청의 두 배와 일치한다. Redis lock timeout은 발생하지 않았다.

## 8. 애플리케이션·JVM·DB

| 지표 | 평균 | 최대 |
| --- | ---: | ---: |
| Delivery process CPU | 63.20% | 73.60% |
| Delivery system CPU | 62.85% | 73.19% |
| JVM heap 사용량 | 300.83MiB | 439.09MiB |
| GC 평균 pause | 15.70ms | 19.38ms |
| GC 5분 최대 pause | 28.64ms | 32.00ms |
| Hikari active | 51.09 | 60 |
| Hikari pending | 24.73 | 42 |
| Tomcat busy ratio | 39.92% | 50.50% |
| Data VM CPU | 93.21% | 99.96% |
| Data VM memory | 14.27% | 14.98% |
| PostgreSQL commit TPS | 785.88 | 999.02 |
| PostgreSQL rollback TPS | 0.05 | 0.42 |

Hikari timeout과 PostgreSQL deadlock은 0건이다. Heap 최대 439.09MiB, GC 최대 pause 32ms로 JVM 메모리 포화 근거는 없다. 반면 Data VM CPU가 다시 100%에 근접하고 Hikari active 60·pending 42가 동시에 관측돼 DB가 현재 병목이다.

## 9. DB 정합성과 배정 한도

| 테이블·계측 | 기준 | 종료 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33,600 | 116,320 | 82,720 |
| `p_delivery_route_histories` | 67,200 | 232,640 | 165,440 |
| `p_delivery_outboxes` | 33,600 | 116,320 | 82,720 |
| `p_delivery_assignment_counts` | 3,300 | 3,300 | 0 |
| 활성 배정 합계 | 5,400 | 170,840 | 165,440 |

성공 요청, 배송 증가, Outbox 증가가 모두 82,720건으로 일치한다. 경로와 활성 배정 증가는 성공 요청의 두 배다. 회사 담당자 최대 51건, 허브 담당자 최대 58건으로 한도 60에 도달하지 않았다.

## 10. Outbox·Redis Streams 회복

트래픽 구간의 Outbox publishable backlog는 최대 79,645건이었다.

| 지표 | 회복 결과 |
| --- | --- |
| Outbox `PENDING/FAILED` | 종료 후 16분 48초 이내 0 |
| Outbox 최종 상태 | `PUBLISHED` 116,320건 |
| AI requested stream | 종료 후 18분 34초 이내 pending 0, lag 0 |
| Delivery generated stream | pending 0, lag 0 |
| Slack generated stream | 종료 후 18분 34초 시점 pending 0, lag 41,376 |
| DB waiting lock / 30초 이상 트랜잭션 | 0 / 0 |
| Redis 배정 lock 잔여 키 | 0 |

요청 경로와 Outbox·AI·Delivery 파이프라인은 정상 회복했다. Slack은 Outbox 발행과 AI 생성보다 소비 속도가 느려 계속 배수 중이다. 다음 부하 테스트 전 Slack lag 0을 확인하고 Stream을 정리해야 한다.

## 11. 로그와 Zipkin

| 항목 | 결과 |
| --- | ---: |
| Delivery WARN | 4건 |
| Delivery ERROR | 0건 |
| 배정 lock timeout | 0건 |
| Hub·User circuit breaker failure rate | 0% |

WARN 4건은 Hub Service 인스턴스가 하나뿐인 환경에서 retry가 이전 인스턴스를 제외하지 못해 같은 인스턴스를 다시 반환했다는 load balancer 경고다. 요청 실패나 회로 차단으로 이어지지 않았다.

Zipkin의 트래픽 구간 최근 성공 trace 표본에서 Delivery root 49개는 평균 33.47ms, 최대 40.60ms였다. Hub 조회는 평균 3.71ms, Hub 경로 조회는 평균 1.14ms였다. Zipkin은 표본 trace 분석에만 사용하고 전체 지연 판정은 k6와 Prometheus를 우선한다.

## 12. 산출물

| 파일 | 내용 |
| --- | --- |
| `local-artifacts/k6-summary.csv` | k6 핵심 결과 |
| `local-artifacts/metrics-summary.csv` | HTTP·JVM·pool·DB·Outbox 지표 |
| `local-artifacts/db-summary.csv` | 기준선 대비 DB 증가분 |
| `local-artifacts/assignment-instrumentation-summary.csv` | 원자적 선점과 캐시 계측 |
| `local-artifacts/loki-summary.csv` | 경고·오류 로그 집계 |
| `local-artifacts/zipkin-summary.csv` | Zipkin span 표본 |
| `local-artifacts/recovery-summary.csv` | DB·Outbox·Redis 회복 스냅샷 |
| `local-artifacts/comparison-summary.csv` | 과거 정상값·직전 Run 비교 |
| `local-artifacts/grafana/dashboard-all-panels.csv` | Grafana 35개 패널·60개 target 통합 CSV |
| `local-artifacts/cloud-run-execution-h2f5w.json` | 본 실행 시간 범위 Cloud Run 원본 로그 |

Grafana 전체 패널은 target 오류 0건으로 저장했다. `local-artifacts`는 로컬 분석 원본이므로 Git 추적에서 제외된다.

## 13. 결론

동일 최종 이미지가 워밍업 후 과거 정상 대표값의 98.50% 처리량을 재현했다. 따라서 최종 구현의 대표 100VU 결과는 이번 Run02의 `172.33 req/s`, 실패율 0%, p95 778.23ms로 채택한다.

직전 Run01은 삭제하지 않고 콜드 상태에서 Hub 회로 차단이 성능을 얼마나 낮출 수 있는지 보여주는 운영 민감도 자료로 유지한다. 다음 성능 한계는 JVM heap이 아니라 Data VM CPU와 DB connection 대기이며, 더 높은 VU나 추가 튜닝은 DB 포화 완화 여부를 기준으로 판단해야 한다.
