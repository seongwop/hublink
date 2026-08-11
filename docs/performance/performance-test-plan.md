# 배송 성능 테스트 방법

## 목적

`POST /internal/deliveries`의 처리량과 지연을 측정하고, 배송 생성 이후 Outbox 발행이 최종적으로 회복되는지 검증한다. 주문·재고·AI 흐름은 배송 성능 실험의 직접 대상에서 제외한다.

## 테스트 모델

| 모델 | 조건 | 용도 |
| --- | --- | --- |
| 닫힌 VU | 100VU, 1분 상승·5분 유지·2분 하강 | 사용자 동시성에서 응답 지연과 자연 처리 한계 확인 |
| 고정 RPS | 30초 상승 후 3분 유지 | 목표 유입량 수용 여부와 dropped iteration 확인 |
| 용량 경계 | 120 RPS부터 단계 증가 | 최종 구현의 안정 처리 상한 확인 |

VU는 응답이 느려지면 다음 요청도 늦어지므로 시스템의 backpressure가 반영된다. 고정 RPS는 응답 속도와 무관하게 목표 요청을 주입하므로 용량을 넘으면 VU 증가, 지연, dropped iteration이 드러난다.

## 공통 조건

- supplier 1개 고정
- receiver 18개 분산
- client sleep 0초
- 담당자 캐시 비교 시 TTL 60초, 최대 Hub 32개
- 코드 변경 비교는 동일 인프라를 사용하고 인프라 실험은 변경 사양을 별도로 기록
- DB 초기 데이터와 담당자 배정 한도 고정

## 실행 순서

1. 배포 이미지 SHA와 환경 변수 확인
2. Delivery health, Eureka 등록, DB·Redis·Kafka 연결 확인
3. Outbox backlog와 Redis Stream pending·lag 확인
4. 짧은 워밍업으로 Hub 경로와 적용된 캐시 예열
5. DB·Redis 기준선 초기화
6. k6 실행
7. k6 종료 후 DB 정합성, Outbox backlog, 회복 시간 수집
8. Prometheus, Loki, Zipkin 근거 수집
9. 대표 실행과 제외 실행 분류

## 워밍업

캐시와 회로 차단기의 콜드 상태가 본 측정을 왜곡하지 않도록 짧은 저부하 요청을 먼저 실행한다. 워밍업 이후 서비스 프로세스는 유지하고 테스트 데이터만 기준선으로 복원한다.

워밍업 실패나 회로 차단기 OPEN이 발생하면 본 테스트를 시작하지 않는다.

## 필수 지표

| 구분 | 지표 |
| --- | --- |
| k6 | 총 요청, 성공·실패, TPS, avg, p95, p99, max, dropped iteration |
| 배송 | HTTP 상태별 RPS, 담당자 선점 시간, 캐시 hit·miss |
| DB | Hikari active·pending, PostgreSQL CPU·connection·lock·TPS |
| JVM | process CPU, heap, GC pause |
| Outbox | Published TPS, publishable backlog 최대·최종값, 회복 시간 |
| 정합성 | 배송·경로·Outbox 증가량과 성공 요청 수 일치 여부 |
| 로그 | lock timeout, 5xx, circuit breaker, 예상하지 못한 예외 |

## 판정

- `PASS`: 요청 실패와 정합성 오류가 없고 목표 부하를 수용
- `WARN`: 기능은 정상이나 latency threshold 또는 자원 상한에 근접
- `FAIL`: timeout, 5xx, 정합성 오류, 미회복 backlog 또는 dropped iteration 발생

고정 RPS에서 실패가 많더라도 환경 장애가 아니라 용량 포화가 명확하면 유효한 한계 결과로 보관한다. 단, 개선 성공 수치로 사용하지 않는다.

## 결과 보관

단계별 대표 결과는 [배송 성능 테스트](delivery/README.md)에 정리한다. 각 문서는 목적, 변경 내용, 테스트 조건, 실행 결과, 모니터링 및 해석, 결론 순서로 작성한다.

반복 Run, 중단 결과, 원본 CSV와 로그는 `delivery/.local-archive/`에 보관하고 Git에서 제외한다.
