# Git 규칙

## 커밋 메시지

`타입: 변경 내용` 형식을 사용한다.

| 타입 | 용도 |
| --- | --- |
| feat | 기능 추가 |
| fix | 오류 수정 |
| perf | 성능 개선 |
| refactor | 동작 변경 없는 구조 개선 |
| test | 테스트 추가와 수정 |
| docs | 문서 변경 |
| ci | GitHub Actions 변경 |
| infra | Terraform과 배포 구조 변경 |
| chore | 빌드와 저장소 관리 |

```text
feat: 주문 생성 API 구현
perf: 배송 담당자 선점 쿼리 원자화
ci: Cloud Run 부하 파라미터 추가
docs: 배송 성능 결과 정리
```

한 커밋에는 하나의 변경 목적만 포함한다. 코드 변경과 그 동작을 설명하는 문서는 같은 커밋에 포함할 수 있다.

## 브랜치

| 브랜치 | 용도 |
| --- | --- |
| `main` | 배포 기준 |
| `develop` | 개발 통합 |
| `feat/*` | 기능 추가 |
| `fix/*` | 오류 수정 |
| `perf/*` | 성능 개선과 부하 테스트 |
| `infra/*` | 인프라와 배포 변경 |
| `docs/*` | 문서 정리 |

```text
feat/order-create
fix/jwt-validation
perf/delivery-assignment-optimization
```
