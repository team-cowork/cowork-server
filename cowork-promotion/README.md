# cowork-promotion

## 역할

cowork 제품과 개발 진행 현황을 소개하는 정적 웹사이트입니다.

- 제품 기능·기술 스택·팀원 소개
- `docs/todo` 기반 개발 진행 현황 페이지 제공

## 스택

- HTML / CSS / JavaScript ES modules
- npm / Node.js 빌드 스크립트
- YAML·XML·Markdown 빌드 데이터
- 페이지별 HTML 번들 (`public/index.html`, `public/todo/`와 문서별 경로) / Vercel

## 포트

| 용도               | 기본 포트 | 비고                   |
|--------------------|-----------|------------------------|
| 개발·미리보기 서버 | `3000`    | `PORT`로 변경          |
| 정적 배포          | 없음      | 호스팅 플랫폼에서 제공 |

## 환경변수

| 변수   | 기본값 | 설명                                              |
|--------|--------|---------------------------------------------------|
| `PORT` | `3000` | 선택. `npm run dev` / `npm run preview` 서버 포트 |

정적 배포 파일에는 필수 런타임 환경변수가 없으며 Config Server·Vault를 사용하지 않습니다.

Vercel의 Root Directory가 `cowork-promotion`이면 `docs/todo/`를 빌드에 포함하도록 **Include source files outside Root Directory in the Build Step**을 활성화해야 합니다.
