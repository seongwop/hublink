# 서비스 포트

## 플랫폼

| 서비스 | 포트 | 역할 |
| --- | ---: | --- |
| eureka-server | 19090 | 서비스 디스커버리 |
| api-gateway | 19091 | API 진입점 |
| config-server | 19092 | 중앙 설정 서버 |

## 도메인 서비스

| 서비스 | 포트 | 역할 |
| --- | ---: | --- |
| user-service | 19093 | 사용자, 인증, 권한 |
| order-service | 19094 | 주문 |
| hub-service | 19095 | 허브와 경로 |
| company-service | 19096 | 업체 |
| product-service | 19097 | 상품 |
| stock-service | 19098 | 재고 |
| delivery-service | 19099 | 배송과 배송 경로 |
| slack-service | 19100 | Slack 알림 |
| ai-service | 19101 | AI 메시지 생성 |

## 데이터와 관측 도구

| 서비스 | 포트 | 역할 |
| --- | ---: | --- |
| PostgreSQL | 5432 | 관계형 데이터베이스 |
| Redis | 6379 | 캐시와 Redis Stream |
| Kafka | 9092 | 이벤트 브로커 |
| Kafka UI | 8082 | topic과 consumer group 조회 |
| Zipkin | 9411 | 분산 추적 |
| Prometheus | 9090 | 메트릭 수집 |
| Loki | 3100 | 로그 저장 |
| Grafana | 3000 | 메트릭과 로그 조회 |

포트 변경 시 Config Repo, Docker Compose, Gateway route와 Eureka 등록 정보를 함께 수정한다.
