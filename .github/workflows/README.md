# GitHub Actions 워크플로

| 파일 | 역할 |
| --- | --- |
| `pr-check.yml` | 변경 서비스 빌드, Compose와 배포 스크립트 검증 |
| `gcp-cicd.yml` | 변경 서비스 이미지 빌드와 GCP VM 배포 |
| `gcp-load-test-sync.yml` | Cloud Run k6 Job 동기화와 부하 파라미터 전달 |

## 배송 재현 모드

`gcp-cicd.yml`의 `delivery-replay`는 과거 배송 이미지에 통제용 캐시 설정만 겹쳐 성능 단계를 재현하기 위한 수동 모드다. 일반 push 배포에서는 사용하지 않는다.

- 원본 commit SHA를 명시
- 캐시 overlay 적용 여부와 TTL 명시
- Outbox polling 간격 명시
- 생성 이미지에 원본 SHA 기반 tag 사용

## 보안과 재현성

- GCP 인증은 Workload Identity Federation 사용
- 비밀번호와 토큰은 GitHub Secrets 또는 Secret Manager 사용
- 애플리케이션 이미지는 commit SHA tag로 배포
- 성능 테스트의 VU, RPS, ramp 조건은 workflow input으로 기록
