# Delivery Logic Distributed Reset

이 디렉터리는 입력값 분산 조건에서 테스트 종료 후 reset SQL을 적용한 결과를 저장한다.

기준:

- `RECEIVER_COMPANY_IDS` 분산
- 종료 후 `db/seed/10-reset-delivery-loadtest.sql` 자동 실행
- 필요 시 시작 전 `PRE_TEST_SQL_FILE` 수동 지정
