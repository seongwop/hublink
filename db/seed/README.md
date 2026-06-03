# HubLink 시나리오 seed

이 폴더는 성능 테스트와 장애 흐름 확인을 위한 기준 데이터와 요청 예시를 관리한다.

## 구성

- `00-reset-scenarios.sql`: 주문, 배송, outbox, 재고 이력 초기화와 재고 수량 복원
- `01-base-scenarios.sql`: 허브, 업체, 상품, 재고, 사용자, 배송 담당자 기준 데이터
- `orders/01-success-order.json`: 정상 주문 시나리오
- `orders/02-stock-fail-order.json`: 재고 부족 시나리오
- `orders/03-delivery-fail-order.json`: 배송 생성 실패 후 재고 보상 시나리오

## 실행 순서

서비스가 먼저 기동되어 Hibernate가 테이블을 만든 뒤 실행한다.

```powershell
$project = "hublink-498115"
$zone = "asia-northeast3-a"

gcloud compute scp db/seed/01-base-scenarios.sql hublink-data-monitor-vm:/tmp/01-base-scenarios.sql --zone $zone --project $project

gcloud compute ssh hublink-data-monitor-vm --zone $zone --project $project --command "sudo docker exec -i hublink-postgres psql -U hublink -d hublink < /tmp/01-base-scenarios.sql"
```

시나리오를 깨끗하게 다시 시작할 때는 reset SQL을 먼저 실행한 뒤 기준 데이터를 다시 넣는다.

```powershell
gcloud compute scp db/seed/00-reset-scenarios.sql hublink-data-monitor-vm:/tmp/00-reset-scenarios.sql --zone $zone --project $project

gcloud compute ssh hublink-data-monitor-vm --zone $zone --project $project --command "sudo docker exec -i hublink-postgres psql -U hublink -d hublink < /tmp/00-reset-scenarios.sql"
```

## 로그인 계정

시드 사용자는 모두 비밀번호 `password`로 로그인할 수 있다.

```json
{
  "username": "master",
  "password": "password"
}
```

가입 승인과 관리 API는 `master` 계정을 사용한다. 주문 시나리오는 `buyer-manager` 계정으로 로그인한 뒤 받은 `accessToken`을 Swagger Authorize 또는 부하 테스트 스크립트의 `Authorization: Bearer ...` 헤더에 넣는다.

## 시나리오 기준

- 정상 주문: 서울 공급업체 상품을 부산 수령업체로 주문
- 재고 부족: 재고가 1개인 상품을 10개 주문
- 배송 실패: 재고는 충분하지만 서울에서 인천으로 가는 허브 경로를 일부러 만들지 않은 주문

주문 요청마다 `X-Order-Key`는 새 UUID를 사용한다. 같은 값을 재사용하면 중복 주문 방지 로직에 걸린다.
