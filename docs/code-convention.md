# 코드 규칙

## Java 스타일

- Java 17을 사용한다.
- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)를 따른다.
- 의존성은 생성자 주입을 사용한다.
- 주석은 코드가 이미 보여 주는 동작보다 설계 이유와 제약을 설명할 때만 작성한다.

## 계층과 트랜잭션

- Controller는 요청 검증과 응답 변환만 담당한다.
- Service는 유스케이스와 트랜잭션 경계를 관리한다.
- Repository는 영속성 조회와 변경에 집중한다.
- 쓰기 작업은 Service 계층의 `@Transactional` 안에서 처리한다.
- 조회 전용 작업은 필요한 경우 `@Transactional(readOnly = true)`를 사용한다.
- 긴 트랜잭션 안에서 외부 API를 호출하지 않는다.

## REST API 규칙

### URI 규칙

- URI는 소문자와 하이픈(`-`)만 사용한다.
- 대문자, 언더스코어(`_`)는 사용하지 않는다.

예시:

```text
/api/v1/hub-routes
/api/v1/order-items
```

### 자원 이름 규칙

- URI에는 동사를 사용하지 않고 명사를 사용한다.
- 자원명은 복수형을 사용한다.

예시:

```text
/users
/orders
/hub-routes
```

## 공통 응답/예외 처리

MSA의 각 서비스가 동일한 JSON 구조의 성공/실패 응답을 반환하도록 `CommonResponse` 및 `@RestControllerAdvice`를 글로벌로 구성한다.

### 성공 응답 예시

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {}
}
```

### 실패 응답 예시

```json
{
  "status": 400,
  "message": "ERROR_CODE",
  "errors": []
}
```


### 예외 처리 규칙

- 공통 예외 처리는 `@RestControllerAdvice`에서 처리한다.
- 모든 API 응답은 `CommonResponse` 형식을 따른다.
- 클라이언트에게 내부 예외 메시지를 그대로 반환하지 않는다.
- 서비스별 응답 구조가 달라지지 않도록 공통 응답 형식을 유지한다.

## 서비스 경계

- 다른 서비스의 DB를 직접 조회하지 않는다.
- 서비스 간 동기 통신은 API, 비동기 통신은 Kafka 또는 Redis Stream을 사용한다.
- 이벤트 consumer는 중복 수신을 고려한다.
