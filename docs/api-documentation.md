# API 문서화 가이드

`cowork-server`는 로컬 프로파일에서 Spring Cloud Gateway가 8개 서비스의 OpenAPI 문서를 하나의 Swagger UI로 집계한다. 이 문서는 2026-07-23의 Gateway 라우팅과 각 서비스 구현을 기준으로 한다.

## 통합 Swagger UI

로컬 Compose를 실행한 뒤 아래 주소로 접속한다.

```text
http://localhost:8080/swagger-ui.html
```

`cowork-config/src/main/resources/configs/cowork-gateway-local.yml`에 등록된 문서는 다음과 같다.

| Swagger 항목    | 대상 서비스            | Gateway 문서 경로            | 서비스 원본 경로    |
|-----------------|------------------------|------------------------------|---------------------|
| `authorization` | `cowork-authorization` | `/v3/api-docs/authorization` | `/swagger/doc.json` |
| `user`          | `cowork-user`          | `/v3/api-docs/user`          | `/v3/api-docs`      |
| `team`          | `cowork-team`          | `/v3/api-docs/team`          | `/v3/api-docs`      |
| `channel`       | `cowork-channel`       | `/v3/api-docs/channel`       | `/v3/api-docs`      |
| `voice`         | `cowork-voice`         | `/v3/api-docs/voice`         | `/swagger/doc.json` |
| `chat`          | `cowork-chat`          | `/v3/api-docs/chat`          | `/api-json`         |
| `notification`  | `cowork-notification`  | `/v3/api-docs/notification`  | `/swagger/doc.json` |
| `preference`    | `cowork-preference`    | `/v3/api-docs/preference`    | `/swagger/doc.json` |

Swagger UI와 `/v3/api-docs/**`는 Gateway에서 인증 없이 접근할 수 있다.

`cowork-roadmap` 문서 프록시는 현재 `dev` Gateway 설정에만 있고 로컬 통합 UI에는 등록되지 않았다. `cowork-project`는 서비스 자체 문서를 제공하지만 Gateway 문서 프록시는 없다. 두 서비스는 아래 직접 접속 주소를 사용한다.

## 서비스별 직접 접속

| 서비스                 | Swagger UI                                 | OpenAPI 문서                             |
|------------------------|--------------------------------------------|------------------------------------------|
| `cowork-authorization` | `http://localhost:8081/swagger/index.html` | `http://localhost:8081/swagger/doc.json` |
| `cowork-user`          | `http://localhost:8082/swagger-ui.html`    | `http://localhost:8082/v3/api-docs`      |
| `cowork-channel`       | `http://localhost:8083/swagger-ui.html`    | `http://localhost:8083/v3/api-docs`      |
| `cowork-voice`         | `http://localhost:8089/swagger/index.html` | `http://localhost:8089/swagger/doc.json` |
| `cowork-team`          | `http://localhost:8085/swagger-ui.html`    | `http://localhost:8085/v3/api-docs`      |
| `cowork-notification`  | `http://localhost:8086/swagger/index.html` | `http://localhost:8086/swagger/doc.json` |
| `cowork-chat`          | `http://localhost:8087/api`                | `http://localhost:8087/api-json`         |
| `cowork-roadmap`       | `http://localhost:8088/swagger-ui.html`    | `http://localhost:8088/v3/api-docs`      |
| `cowork-project`       | `http://localhost:8084/swagger-ui.html`    | `http://localhost:8084/v3/api-docs`      |
| `cowork-preference`    | 별도 UI 없음                               | `http://localhost:9001/swagger/doc.json` |

Compose 기본 구성에서는 `cowork-project`는 `8084`, `cowork-voice`는 `8089`를 컨테이너와 호스트에서 동일하게 사용한다.

## 문서 생성과 갱신

### Go 서비스

`cowork-authorization`, `cowork-notification`, `cowork-voice`는 swaggo로 문서를 생성한다. 핸들러 어노테이션을 변경한 뒤 해당 서비스에서 실행한다.

```bash
make swagger-gen
go build ./...
```

최초 설정이 필요하면 루트에서 세 서비스를 한 번에 준비할 수 있다.

```bash
make setup
```

생성되는 `docs/docs.go`, `docs/swagger.json`, `docs/swagger.yaml`도 API 변경과 함께 커밋한다.

### Spring Boot 서비스

`cowork-team`, `cowork-channel`, `cowork-project`, `cowork-roadmap`은 springdoc과 코드의 OpenAPI 어노테이션을 사용한다. 코드를 다시 빌드하고 서비스를 재기동하면 문서가 반영된다.

### Elixir 서비스

`cowork-user/lib/cowork_user/open_api.ex`의 `CoworkUser.OpenAPI` 명세를 수정하고 서비스를 재기동한다.

### NestJS 서비스

`cowork-chat`은 `@nestjs/swagger` 어노테이션에서 OpenAPI 문서를 생성한다. REST API, GraphQL, Socket.IO를 함께 제공하므로 "WebSocket 전용 서비스"가 아니다.

AsyncAPI 문서는 정적 파일 `cowork-chat/public/asyncapi.json`으로 관리하며 직접 접속 주소는 다음과 같다.

```text
http://localhost:8087/asyncapi.json
```

Socket.IO 이벤트를 변경하면 핸들러뿐 아니라 이 파일도 함께 갱신한다.

### Vert.x 서비스

`cowork-preference`는 `src/main/resources/openapi.json`을 정적으로 제공한다. 라우터나 DTO를 변경할 때 이 파일을 수동으로 함께 갱신한다.

## Try it out과 인증

Gateway를 통한 비공개 API 호출에는 JWT Bearer 토큰이 필요하다. `Authorize`에서 토큰을 설정하면 Gateway가 검증한 뒤 아래 헤더를 하위 서비스에 덮어쓴다.

```text
X-User-Id: <JWT subject>
X-User-Role: <JWT role>
```

서비스의 Swagger UI를 직접 호출하면 Gateway를 거치지 않으므로, 문서에 노출된 `X-User-Id`와 `X-User-Role` 헤더를 테스트 목적에 맞게 직접 입력해야 한다. 운영 환경에서는 하위 서비스에 직접 접근할 수 있는 경로를 노출하지 않는다.
