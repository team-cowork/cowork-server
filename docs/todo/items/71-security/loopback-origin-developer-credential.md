# 로컬 개발 origin 요청의 개발자 자격증명 검증

- **서비스**: cowork-gateway, cowork-authorization, cowork-channel, cowork-config, SeaweedFS, CD
- **우선순위**: 🟠 중간
- **현재 상태**: 운영 `PUBLIC_WEB_ORIGINS`에 `http://localhost:3000`이 들어가 있어, 그 origin에서 온 요청은 별도 확인 없이 운영 origin과 같은 수준으로 신뢰된다

## 문제

PR #515에서 로컬 프론트 개발을 위해 `deploy/prod/slots/config.json`의 `PUBLIC_WEB_ORIGINS`에
`http://localhost:3000`을 추가했다. `cowork-config`는 이 값을 `spring.cloud.config.server.overrides`로
전달하므로 Gateway HTTP CORS(`allowCredentials: true`), `WsOriginFilter`의 WebSocket origin 허용 목록,
`cowork-channel` OAuth 복귀 origin, SeaweedFS 버킷 CORS가 모두 이 origin을 허용한다. 같은 PR에서
WebSocket 인증 쿠키 `cowork_ws_token`(`cowork-authorization/internal/handler/auth_handler.go`)도
`SameSite=None`으로 바뀌었다.

이 허용은 origin 문자열만으로 판단한다. 개발진이 아닌 사용자의 브라우저에서도 `localhost:3000`에서
동작하는 아무 페이지가 운영 origin과 같은 대우를 받는다. REST는 `Authorization` 헤더 인증이라
쿠키만으로는 인증되지 않지만, `/ws/**`는 쿠키로 인증되므로 그 페이지가 해당 사용자의 운영
WebSocket에 연결할 수 있다.

loopback HTTP origin에서 온 요청은 사전에 약정한 개발자 자격증명을 함께 보낸 경우에만 허용하여,
개발 편의는 유지하면서 허용 범위를 개발진으로 좁힌다.

## 경로별 제약

헤더 하나를 모든 경로에 똑같이 적용할 수는 없다. 아래 제약은 브라우저 동작에서 나온 것이며,
각 경로의 실제 동작은 아직 검증하지 않았다.

| 경로 | 제약 | 검토할 방향 |
|------|------|-------------|
| CORS preflight(`OPTIONS`) | 브라우저가 커스텀 헤더 값을 싣지 않고 이름만 `Access-Control-Request-Headers`로 보낸다 | preflight는 지금처럼 통과시키고 실제 요청에서 검증한다 |
| REST(`/api/**`) | 프론트가 헤더를 직접 넣을 수 있다 | Gateway 전역 필터에서 loopback HTTP `Origin`이면 자격증명 헤더를 검증하고, 없거나 틀리면 거부한다. CORS `allowedHeaders`에 헤더 이름이 포함되는지 확인한다 |
| WebSocket(`/ws/**`) | 브라우저 WebSocket API는 커스텀 헤더를 보낼 수 없다 | subprotocol, 핸드셰이크 직전 발급하는 단기 티켓 등 헤더 외 전달 방식을 정한다 |
| OAuth 복귀 | 복귀 자체는 리다이렉트라 헤더가 없다 | `return_origin`을 받는 authorize 요청(`OAuthAccountController`) 시점에 검증한다 |
| 저장소 업로드(`/api/storage/**` PUT) | Gateway에서 `permitAll`이고 `StorageRelay`를 거친다 | Gateway 전역 필터 적용 범위에 포함할지, presigned URL 서명 헤더로 묶을지 정한다 |

## 자격증명 보관

자격증명 값은 개발진에게 전파하고 운영 서비스에 주입한다. 사용자 요청은 GitHub Secrets로
주입하는 것이었으나, `.claude/rules/config.md`는 애플리케이션 비밀을 Vault로 공급하고 GitHub Secrets에는
배포 자격증명만 두도록 정하고 있다. 보관 위치를 정할 때 이 규칙과의 관계를 함께 결정한다.

## 할 일

### 정책 결정

- 자격증명 형식(고정 값, 개발자별 값)과 교체 주기를 정한다.
- 보관 위치(Vault, GitHub Secrets)를 `.claude/rules/config.md`와 맞춰 정한다.
- WebSocket과 저장소 업로드의 자격증명 전달 방식을 위 표에서 고른다.

### 구현

- Gateway에 loopback HTTP origin 요청의 자격증명 검증 필터를 추가한다.
- WebSocket 핸드셰이크와 OAuth authorize 요청에 정한 방식을 적용한다.
- 자격증명을 운영 서비스에 주입하는 배포 경로를 추가한다.
- 운영 프론트 빌드에 자격증명이 포함되지 않도록 프론트 개발 환경 변수 이름과 전파 절차를 정리한다.

## 검증

- `Origin: http://localhost:3000`으로 자격증명 없이 보낸 REST 요청과 WebSocket 핸드셰이크가 거부되는지 확인한다.
- 같은 origin에서 올바른 자격증명을 보낸 요청이 통과하는지 확인한다.
- `https://service.gsmsv.site:22118`과 네이티브 앱(`Origin` 없음) 요청이 자격증명 없이 기존대로 동작하는지 확인한다.

## 완료 조건

- loopback HTTP origin에서 자격증명 없이 온 REST, WebSocket, OAuth authorize 요청은 거부된다.
- 운영 HTTPS origin과 `Origin`이 없는 요청은 자격증명 검증 대상이 아니다.
- 자격증명은 저장소와 운영 프론트 산출물에 평문으로 남지 않는다.
