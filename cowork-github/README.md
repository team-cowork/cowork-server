# cowork-github

## Overview

현재 저장소는 기본 NestJS 애플리케이션 템플릿을 기반으로 한 단일 서비스입니다.
아직 GitHub App 인증, Kafka 소비, 이슈 생성 로직은 구현되어 있지 않습니다.

현재 포함된 기능:

- `GET /` 엔드포인트
- 기본 단위 테스트
- 기본 e2e 테스트
- TypeScript, ESLint, Prettier 기반 개발 환경

## Project Structure

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

## Environment Variables

현재 코드에서 실제로 사용하는 환경 변수는 `PORT` 하나입니다.

```env
PORT=3000
```

## Install

```bash
npm install
```

## Run

```bash
# development
npm run start:dev

# production build
npm run build
npm run start:prod
```

서버 기본 주소:

- `http://localhost:3000`

## Test

```bash
# unit test
npm test

# e2e test
npm run test:e2e

# coverage
npm run test:cov
```

## Lint

```bash
npm run lint
```

## CI/CD

이 저장소의 GitHub Actions는 현재 단일 NestJS 서비스 기준으로 구성됩니다.

- `develop` 브랜치: CI 실행
- `main` 브랜치: CI 실행
- `main` 푸시 성공 후: 태그 및 GitHub Release 생성

## Notes

- `.env`, `.pem` 같은 비밀값 파일은 커밋하지 않습니다.
- 문서에 없는 GitHub 연동 기능은 아직 구현되지 않았습니다.
