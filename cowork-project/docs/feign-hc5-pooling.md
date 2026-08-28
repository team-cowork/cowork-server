# GitHub App 연동 Feign 클라이언트 커넥션 풀링

## 배경

여러 팀의 GitHub 이슈·PR 댓글 작성 빈도가 늘어날 것을 고려해, `GithubAppClient`(Feign)의 동기 HTTP 호출이 병목이 될 수 있다는 우려가 제기되었다. Kafka 비동기 전환도 검토했으나 다음 이유로 원인 조사와 성능 튜닝을 먼저 진행했다.

- Kafka로 전환해도 GitHub 실제 API 처리량(rate limit) 자체는 늘어나지 않는다.
- 현재 REST 계약(`POST .../comments` → `201` + 생성된 댓글 즉시 반환)을 깨야 해서 변경 비용이 크다.

## 조사

`mvnw dependency:tree`로 확인한 결과, `cowork-project`는 `spring-cloud-starter-openfeign`만 의존하고 있었고 `feign-hc5`(Apache HttpClient5용 Feign 어댑터)가 없었다. `httpclient5` 라이브러리 자체는 `spring-cloud-starter-netflix-eureka-client`가 전이 의존성으로 이미 끌어오고 있었지만, Feign이 이를 사용하려면 별도 어댑터가 필요해서 실제로는 풀링이 없는 기본 `HttpURLConnection` 클라이언트로 동작하고 있었다.

## 변경 사항

- `cowork-project/pom.xml`에 `io.github.openfeign:feign-hc5` 의존성 추가 (버전은 `feign-core`와 동일하게 `13.6.1`로 자동 정렬)
- 기존 `feign.client.config`의 `github-app` 개별 timeout 설정은 그대로 유지
- 풀 크기는 Spring Cloud OpenFeign 기본값(전체 200 / 호스트당 50)을 그대로 사용 — 실측 근거 없이 임의로 조정하지 않음

## 검증

- `mvnw compile`, `test-compile`, `GithubAppErrorDecoderTest`, `GithubAppCallExecutorTest` 모두 통과
- 로컬에 0.3초 지연 응답 서버를 띄우고 `HttpURLConnection`(변경 전 기본값)과 `HttpClient5` 풀링 클라이언트로 각각 20개 동시 요청을 발생시켜 비교:

  ```
  [JDK HttpURLConnection, default]        20 concurrent requests took 486 ms
  [Apache HttpClient5, pooled max=50]     20 concurrent requests took 441 ms
  ```

  유의미한 차이는 확인되지 않았다. "JDK 기본 클라이언트는 호스트당 동시 연결이 5개로 제한된다"는 통념도 부정확한 것으로 확인했다 — `http.maxConnections`(기본값 5)는 재사용을 위해 캐싱하는 idle keep-alive 연결 수 제한이며, 새로운 동시 연결을 여는 것 자체를 막지 않는다.

## 결론 및 후속 조치

- `feign-hc5`는 유지한다. 명시적인 풀 관리와 연결 재사용이라는 점에서 더 안전한 기본값이지만, 성능 개선을 보장하지는 않는다.
- 완전한 versioned event feed가 없는 외부 GitHub provider의 request-scoped 원본 조회만 HTTP 예외로 유지한다. cowork 내부의 durable 저장소·webhook 상태 조회는 compacted `project.github-repo.event` projection으로 전환한다.
- 댓글·라벨 쓰기의 즉시 반환 계약만으로 HTTP를 정당화하지 않는다. 이 경로는 외부 github-app의 command/result·멱등 계약이 없어 보류된 계약 갭이며, 양쪽 저장소를 함께 바꿀 때 Kafka command로 전환한다.
- `cowork-chat`의 슬래시 커맨드(`github.issue.create`)가 `cowork-project`의 `resolveForModify` 권한 체크를 우회하는 별도 이슈는 이번 스코프에서 제외했다 — 별도로 다뤄야 한다.
