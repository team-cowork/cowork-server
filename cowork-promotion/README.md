# cowork-promotion

## 역할

cowork 제품을 소개하는 웹사이트입니다.

- 제품 기능·기술 스택·팀원 소개 및 개발 성과

## 스택

- HTML / CSS / JavaScript ES modules
- npm / Node.js 빌드 스크립트
- YAML·XML·JSON 빌드 데이터
- 정적 HTML, 해시 기반 CSS·JS 에셋 / Vercel
- GitHub 성과 자동 갱신 / Vercel Node.js Function

## 환경변수

| 변수 | 설명 |
|------|------|
| `SITE_URL` | 선택. 빌드 시 canonical·`og:url`에 사용할 HTTP(S) origin. 지정하면 Vercel 값보다 우선 |
| `GITHUB_TOKEN` | 서버 전용 GitHub 읽기 토큰. 성과 자동 갱신에 사용하며 미설정·실패 시 저장된 수치를 표시. Vercel 환경변수로 설정 |
