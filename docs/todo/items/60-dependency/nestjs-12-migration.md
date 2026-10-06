# cowork-chat NestJS 12 전환

- **서비스**: cowork-chat
- **우선순위**: 🟡 낮음
- **현재 상태**: NestJS 11 계열을 유지하며 `@nestjs/swagger`는 `^11.4.7`로 고정하고 하위 `js-yaml`만 `overrides`로 `5.4.2`를 적용했다.

## 문제

`cowork-chat/package.json`은 `@nestjs/common`·`@nestjs/core`와 플랫폼 패키지를 `^11.2.5`로, `@nestjs/apollo`·`@nestjs/graphql`을 `^13.4.5`로 선언한다.
Dependabot PR #443은 js-yaml 보안 패치(`5.3.0` → `5.4.2`)를 위해 `@nestjs/swagger`를 `12.0.2`로 올렸다.
이 버전은 peer로 `@nestjs/common`·`@nestjs/core` `^12.0.0`을 요구해 `npm ci`가 `ERESOLVE`로 실패했다.

PR #445는 `@nestjs/swagger`를 `^11.4.7`로 되돌리고 `overrides`의 `@nestjs/swagger` 항목으로 `js-yaml`을 `5.4.2`에 고정했다.
`@nestjs/swagger@11.4.7`이 선언한 `js-yaml` 버전은 `5.3.0`이므로 이 override는 패키지가 검증하지 않은 조합이다.
`npm ci`, `npm run build`, `npm run lint`, `npm test`는 통과했으나 Swagger 문서 생성·YAML 출력은 아직 검증하지 않았다.

이 저장소에는 `.github/dependabot.yml`이 없어 Dependabot 보안 업데이트가 같은 메이저 업데이트를 다시 제안할 수 있다.

## 버전 고정 현황 (2026-10-06)

| 패키지 | 현재 | 최신 | NestJS 12 peer 지원 |
|---|---|---|---|
| `@nestjs/common`, `@nestjs/core` | 11.2.5 | 12.1.2 | 대상 |
| `@nestjs/platform-express`, `@nestjs/platform-socket.io`, `@nestjs/websockets`, `@nestjs/microservices`, `@nestjs/testing` | 11.2.5 | 12.1.2 | 12.x에서 `^12.0.0` 요구 |
| `@nestjs/swagger` | 11.4.7 | 12.0.2 | 12.x에서 `^12.0.0` 요구 |
| `@nestjs/apollo`, `@nestjs/graphql` | 13.4.5 | 14.0.3 | 14.x에서 `^12.0.0` 요구 |
| `@nestjs/config` | 12.0.0 | 12.0.1 | `^11.0.0 \|\| ^12.0.0` |
| `@nestjs/jwt` | 12.0.2 | 12.0.2 | `^12.0.0` 포함 |
| `@nestjs/mongoose` | 12.0.0 | 12.0.0 | `^11.0.0 \|\| ^12.0.0` |
| `@willsoto/nestjs-prometheus` | 6.1.1 | 6.1.1 | `^12.0.0` 포함 |
| `nestjs-pino` | 5.2.0 | 5.3.1 | 5.3.1에서 `^12.0.2` 포함 |
| `dicoshot-nest` | 0.5.0 | 0.5.0 | 미지원 (`^10.0.0 \|\| ^11.0.0`) |
| `js-yaml` (`@nestjs/swagger` 하위) | 5.4.2 (override) | 5.4.2 | 해당 없음 |

`dicoshot-nest`가 NestJS 12 peer를 지원하지 않는 것이 현재 전환 차단 요인이다.

## 할 일

### 선행 확인

- `dicoshot-nest`의 NestJS 12 지원 릴리스를 확인하거나 대체·제거 방안을 정한다.
- NestJS 12, `@nestjs/graphql`·`@nestjs/apollo` 14, `@nestjs/swagger` 12의 breaking change를 공식 마이그레이션 문서로 확인한다.

### 전환

- NestJS 공통·플랫폼 패키지와 `@nestjs/testing`을 12.x로 함께 올린다.
- `@nestjs/apollo`·`@nestjs/graphql`을 14.x, `@nestjs/swagger`를 12.x, `nestjs-pino`를 5.3.1 이상으로 올린다.
- `overrides`의 `@nestjs/swagger` → `js-yaml` 항목을 제거한다.
- 재발 방지가 필요하면 `.github/dependabot.yml`에서 `@nestjs/*` 메이저 업데이트를 묶음 처리하거나 제외하는 방안을 정한다.

## 검증

- `npm ci`가 `--legacy-peer-deps` 없이 통과한다.
- `npm run build`, `npm run lint`, `npm test`가 통과한다.
- Swagger 문서, GraphQL 스키마, Socket.IO gateway, Kafka microservice가 기동 후 정상 동작한다.

## 완료 조건

- `cowork-chat`의 NestJS 공통·플랫폼 패키지가 12.x로 정렬되어 있다.
- `package.json`의 `overrides`에 `@nestjs/swagger` 하위 `js-yaml` 고정이 남아 있지 않다.
- 의존성 설치가 peer 충돌 없이 이루어진다.
