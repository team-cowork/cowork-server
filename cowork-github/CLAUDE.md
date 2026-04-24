# cowork-github

## Current State

이 저장소는 현재 기본 NestJS 단일 애플리케이션입니다.
GitHub App 인증, Kafka 연동, GitHub Issue 생성 기능은 아직 구현되지 않았습니다.

## Runtime

- Framework: NestJS
- Language: TypeScript
- HTTP server: Express 기반 Nest 기본 서버
- Default port: `3000`

## Current Structure

```text
src/
├── app.controller.ts
├── app.controller.spec.ts
├── app.module.ts
├── app.service.ts
└── main.ts

test/
├── app.e2e-spec.ts
└── jest-e2e.json
```

## Current Behavior

- `GET /` 요청에 `Hello World!` 문자열을 반환합니다.
- `main.ts`에서 `process.env.PORT ?? 3000` 값을 사용해 서버를 실행합니다.
- 단위 테스트와 e2e 테스트가 기본 예제로 포함되어 있습니다.

## Environment Variables

현재 코드에서 실제로 참조하는 환경 변수:

```env
PORT=3000
```

## Development Rules

### Security

- `.env`, `.pem`, 토큰 등 비밀값은 저장소에 커밋하지 않습니다.
- 테스트용 키라도 실키 형태의 개인키 파일은 장기 보관하지 않습니다.

### Code

- 현재는 `app.*` 중심의 기본 구조만 존재하므로, 새 기능은 목적별 모듈로 분리해 추가합니다.
- 환경 변수 사용이 늘어나면 `@nestjs/config`를 도입한 뒤 직접 `process.env` 접근을 줄입니다.
- 예제 코드를 실제 기능 코드로 교체할 때는 테스트도 함께 갱신합니다.

### Testing

- `npm test`는 단위 테스트를 실행합니다.
- `npm run test:e2e`는 HTTP 레벨 e2e 테스트를 실행합니다.
- `npm run build`가 항상 통과하도록 유지합니다.

## GitHub Actions

현재 워크플로는 단일 서비스 저장소 기준입니다.

- `cowork-stage-ci.yml`
  - `develop` 대상 PR/Push에서 설치, 린트, 빌드, 테스트를 수행합니다.
- `cowork-prod-ci.yml`
  - `main` 대상 PR/Push에서 설치, 린트, 빌드, 테스트를 수행합니다.
- `cowork-prod-cd.yml`
  - `main` 푸시 후 검증이 끝나면 날짜 기반 태그와 GitHub Release를 생성합니다.
- `cowork-pr-cleanup.yml`
  - 머지된 PR에서 대기 라벨을 제거합니다.

## Planned Work

향후 이 저장소를 GitHub 연동 서비스로 확장할 수 있지만, 현재 문서와 워크플로는 구현된 코드만 기준으로 유지합니다.
