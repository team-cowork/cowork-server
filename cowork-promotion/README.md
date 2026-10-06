# cowork-promotion

## 역할

cowork 제품과 개발 진행 현황을 소개하는 정적 웹사이트입니다.

- 제품 기능·기술 스택·팀원 소개
- `docs/todo` 기반 개발 진행 현황 페이지 제공

## 스택

- HTML / CSS / JavaScript ES modules
- npm / Node.js 빌드 스크립트
- YAML·XML·Markdown 빌드 데이터
- 페이지별 정적 HTML, 해시 기반 CSS·JS·JSON 에셋 / Vercel

## 린트

모듈 디렉터리에서 실행합니다. `XO` 버전은 잠금 파일과 함께 고정하며, 에디터의 ESLint 확장은 `eslint.config.mjs`를 통해 같은 `xo.config.mjs` 설정을 사용합니다.

```sh
npm ci
npm run lint -- --max-warnings=0
npm run lint:fix
npm run build
```

JS/TS 소스와 린터 설정을 검사하며, HTML·CSS·Markdown·JSON은 대상에서 제외합니다. 들여쓰기는 `.editorconfig`와 같은 공백 4칸입니다. 브라우저 소스와 Node.js 빌드 스크립트의 전역 변수를 구분합니다. 생성된 `public/` 에셋은 검사하지 않습니다.

프로젝트별 예외와 이유는 `xo.config.mjs`에 기록합니다. 자동 수정 후에는 diff와 빌드를 확인해야 합니다. Stage/Prod CI의 Node 작업은 빌드 전에 린트를 실행하며 경고도 실패로 처리합니다.

## 포트

| 용도               | 기본 포트 | 비고                   |
|--------------------|-----------|------------------------|
| 개발·미리보기 서버 | `3000`    | `PORT`로 변경          |
| 정적 배포          | 없음      | 호스팅 플랫폼에서 제공 |

## 환경변수

| 변수       | 기본값                           | 설명                                                               |
|------------|----------------------------------|--------------------------------------------------------------------|
| `PORT`     | `3000`                           | 선택. `npm run dev` / `npm run preview` 서버 포트                  |
| `SITE_URL` | Vercel 운영 도메인 → 배포 도메인 | 선택. canonical과 `og:url`에 사용할 HTTP(S) origin. 경로 없이 지정 |

정적 배포 파일에는 필수 런타임 환경변수가 없으며 Config Server·Vault를 사용하지 않습니다.

Vercel의 Root Directory가 `cowork-promotion`이면 `docs/todo/`를 빌드에 포함하도록 **Include source files outside Root Directory in the Build Step**을 활성화해야 합니다.
