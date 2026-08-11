# Grafana 대시보드

## 디렉터리

| 경로 | 용도 |
| --- | --- |
| `json/` | Prometheus와 Loki를 조회하는 실시간 대시보드 |
| `comparison-100vu/` | 100VU 8분 전후 비교 시계열 |
| `comparison-fixed-rps/` | 고정 RPS 전후 비교와 용량 측정 시계열 |

비교 대시보드는 서로 다른 실행 시간을 경과 시간 기준으로 정렬한 스냅샷이다. Grafana TestData CSV를 JSON에 포함하므로 원본 CSV 없이도 그래프를 확인할 수 있다.

## 확인 사항

- 텍스트 패널의 이미지 SHA와 테스트 조건 확인
- 100VU 결과와 고정 RPS 결과를 직접 혼합하지 않음
- 실패율이 높은 실행은 처리량 개선보다 포화 지점 분석에 사용
- DB 사양이나 VM 배치가 다른 결과는 누적 변화로 표시

원본 CSV와 생성 도구는 `docs/performance/delivery/.local-archive/`에 보관한다. 비교 대시보드는 실수로 수정되지 않도록 `allowUiUpdates: false`와 `editable: false`를 사용한다.
