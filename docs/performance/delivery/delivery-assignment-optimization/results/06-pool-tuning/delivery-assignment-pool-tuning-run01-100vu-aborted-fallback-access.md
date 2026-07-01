# Delivery Assignment Pool Tuning Run 01 - 100VU Aborted

## 1. 목적

Hikari pool 설정 조정 후 100VU 부하 테스트를 다시 실행해 다음을 확인하려 했다.

- PostgreSQL `too many clients already` 문제가 해소됐는지
- `delivery-service` max pool 20 설정이 실제 적용됐는지
- 테스트와 직접 관련 없는 서비스의 idle connection 점유가 줄었는지
- mixed bulk upsert 이후 100VU 결과가 Hikari pool 조정으로 개선되는지

결론부터 말하면, Hikari 설정 적용은 확인됐지만 부하 테스트는 연속 502 오류로 중단했다. 따라서 이 run은 성능 비교 결과로 사용하지 않는다.

## 2. 테스트 조건

| 항목 | 값 |
| --- | --- |
| 테스트명 | Delivery Assignment Pool Tuning Run 01 - 100VU Aborted |
| 시작 시간 | 2026-06-30 14:08:51 KST |
| 중단 시간 | 2026-06-30 14:09:46 KST |
| 실행 시간 | 약 51.6초 |
| 대상 API | `POST /internal/deliveries` |
| k6 script | `delivery-create-logic-load.js` |
| VU / duration | 최대 100 VU / 원래 8분 예정 |
| 실제 도달 VU | 84 VU |
| 부하 패턴 | 1m ramp-up, 5m 유지, 2m ramp-down |
| 입력 조건 | distributed, no-sleep, supplier 1개 고정, receiver 18개 분산 |
| baseline seed | `db/seed/14-reset-delivery-perf-baseline.sql` |
| client sleep | `SLEEP_SECONDS=0` |
| lock wait | `delivery.assignment.lock-wait=2s` |
| 변경 사항 | 서비스별 Hikari max pool / minimum idle 조정 |
| 판정 | invalid / aborted |

실행 명령:

```bash
PRE_TEST_SQL_FILE=db/seed/14-reset-delivery-perf-baseline.sql SUPPLIER_COMPANY_ID='20000000-0000-0000-0000-000000000001' RECEIVER_COMPANY_IDS='20000000-0000-0000-0000-000000000002,20000000-0000-0000-0000-000000000003,20000000-0000-0000-0000-000000000010,20000000-0000-0000-0000-000000000011,20000000-0000-0000-0000-000000000012,20000000-0000-0000-0000-000000000013,20000000-0000-0000-0000-000000000014,20000000-0000-0000-0000-000000000015,20000000-0000-0000-0000-000000000016,20000000-0000-0000-0000-000000000017,20000000-0000-0000-0000-000000000020,20000000-0000-0000-0000-000000000021,20000000-0000-0000-0000-000000000022,20000000-0000-0000-0000-000000000023,20000000-0000-0000-0000-000000000024,20000000-0000-0000-0000-000000000025,20000000-0000-0000-0000-000000000026,20000000-0000-0000-0000-000000000027' PRODUCT_NAME='k6-test-product' SLEEP_SECONDS=0 STAGES='[{"duration":"1m","target":100},{"duration":"5m","target":100},{"duration":"2m","target":0}]' ./run-k6.sh delivery-create-logic-load.js
```

## 3. Hikari 설정 적용 확인

부하 테스트 실행 전 actuator/prometheus 지표로 설정 적용을 확인했다.

| 서비스 | max | min idle | 테스트 전 idle |
| --- | ---: | ---: | ---: |
| `delivery-service` | 20 | 3 | 3 |
| `hub-service` | 10 | 2 | 2 |
| `user-service` | 10 | 2 | 2 |
| `company-service` | 10 | 2 | 2 |
| `product-service` | 5 | 1 | 1 |
| `order-service` | 5 | 1 | 1 |
| `stock-service` | 5 | 1 | 1 |
| `slack-service` | 3 | 1 | 1 |
| `ai-service` | 3 | 1 | 1 |

이전에는 각 서비스가 기본 `minimumIdle = maximumPoolSize`에 가깝게 동작하면서 idle connection만으로 PostgreSQL connection을 거의 소진했다. 이번 설정 적용 후에는 테스트 시작 전 idle connection 점유가 크게 줄었다.

## 4. k6 중단 결과

초기부터 `DELIVERY_013`, 이후 `DELIVERY_011` 502 응답이 연속 발생해 테스트를 중단했다.

| 항목 | 값 |
| --- | ---: |
| 총 요청 수 | 6735 |
| HTTP TPS | 130.55 req/s |
| 체크 성공 | 9 |
| 체크 실패 | 6726 |
| 체크 성공률 | 0.13% |
| HTTP 실패율 | 99.86% |
| 평균 응답 시간 | 298.96ms |
| p95 | 694.45ms |
| p99 | 1.32s |
| 최대 응답 시간 | 15.89s |
| 중단 시점 VU | 84 |
| interrupted iterations | 86 |

위 TPS와 latency는 정상 처리 성능을 의미하지 않는다. 대부분의 요청이 빠르게 502로 실패했기 때문에 값이 왜곡됐다.

대표 오류:

```text
status=502
message=DELIVERY_013
허브 서비스와 통신할 수 없습니다.

status=502
message=DELIVERY_011
사용자 서비스와 통신할 수 없습니다.
```

## 5. DB 반영 결과

pre-test reset SQL은 정상 실행됐다. 테스트는 중단됐지만 일부 요청은 DB에 반영됐다.

| 항목 | baseline | 중단 후 | 증가 |
| --- | ---: | ---: | ---: |
| `p_deliveries` | 33600 | 33647 | 47 |
| `p_delivery_route_histories` | 67200 | 67294 | 94 |
| `p_delivery_outboxes` | 33600 | 33647 | 47 |
| `PUBLISHED` outbox | 33600 | 33647 | 47 |

집계 테이블:

| assignment type | 중단 후 | baseline 대비 증가 |
| --- | ---: | ---: |
| `COMPANY_DELIVERY` | 3647 | 47 |
| `HUB_DELIVERY` | 1847 | 47 |

k6 체크 성공 9건보다 DB 반영 증가분이 더 크다. 일부 요청은 DB commit 이후 응답 처리 또는 클라이언트 관점에서 실패로 집계됐을 가능성이 있다. 이 때문에 이 run은 성공/실패 수와 DB 증가분을 성능 비교 근거로 사용하지 않는다.

## 6. 로그 분석

`delivery-service` 로그에서 Resilience4j fallback 호출 중 접근 오류가 확인됐다.

```text
java.lang.IllegalAccessException:
class io.github.resilience4j.spring6.fallback.FallbackMethod cannot access a member of class
com.msa.delivery_service.client.DeliveryExternalService with modifiers "private"
```

코드 확인 결과 `DeliveryExternalService`의 fallback 메서드들이 모두 `private`이었다.

```java
private HubResponse getHubFallback(UUID hubId, Throwable e)
private List<HubRouteResponse> getHubRoutesFallback(DeliveryRequest request, Throwable e)
private HubManagerResponse getHubManagerFallback(UUID departureHubId, Throwable e)
private List<DeliveryManagerResponse> getDeliveryManagersFallback(List<UUID> hubIds, Throwable e)
```

부하 중 hub/user 호출이 timeout, circuit breaker, 또는 downstream 지연으로 fallback 경로에 들어가면, fallback 메서드 접근 오류 때문에 정상적인 fallback 예외 변환도 실패한다. 결과적으로 502 응답이 빠르게 대량 발생했고, k6 요청 속도가 비정상적으로 치솟았다.

동시에 Redis lock timeout도 다수 확인됐다.

```text
event=DELIVERY_ASSIGNMENT_LOCK_TIMEOUT
failedKey=lock:delivery:company:...
waitMillis=2000
```

다만 이번 run은 fallback 접근 오류로 조기에 무너졌기 때문에 lock timeout 수치도 정상 성능 분석 지표로 사용하지 않는다.

## 7. 해석

```text
INVALID_RUN

- Hikari pool 설정은 적용됨
- PostgreSQL connection 고갈 문제는 테스트 시작 전 기준으로 해소됨
- reset SQL은 정상 실행됨
- 하지만 100VU ramp-up 초반부터 502가 연속 발생
- Resilience4j fallback 메서드 private 접근 오류가 확인됨
- 51.6초 시점에 테스트를 수동 중단함
- 이 run은 성능 개선/악화 비교에 사용하지 않음
```

이번 결과는 Hikari pool 튜닝의 성능 효과를 판단하기에는 부적절하다. 먼저 fallback 접근 오류를 제거해야 한다. 그 이후 같은 100VU 조건으로 다시 측정해야 Hikari pool 조정 효과를 비교할 수 있다.

## 8. 다음 액션

1. `DeliveryExternalService`의 fallback 메서드를 `private`이 아닌 접근자로 변경한다.
   - Resilience4j가 reflection으로 접근할 수 있도록 `public` 또는 적어도 접근 가능한 형태로 둔다.
2. 같은 100VU 명령으로 재측정한다.
3. 재측정에서도 502가 발생하면 그때는 다음을 분리해서 확인한다.
   - hub/user Feign timeout
   - circuit breaker open 여부
   - hub-service -> company-service 호출 지연
   - Redis lock timeout 비율
   - Hikari active/pending 추이

