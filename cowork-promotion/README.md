# cowork-promotion

## 역할

cowork 제품을 소개하는 정적 프로모션 웹사이트입니다. 루트 페이지는 prerender되며 별도 백엔드 의존성이 없습니다.

## 스택

- Nuxt 4 + Vue 3
- Tailwind CSS
- GSAP 애니메이션
- npm(`package-lock.json`)

## 실행

```bash
npm install
npm run dev
```

- 개발 서버 기본 포트: `3000`
- 정적 생성: `npm run generate`
- 프로덕션 빌드/미리보기: `npm run build`, `npm run preview`

## 설정 공급

현재 정적 페이지는 백엔드·Config Server·Vault 의존성이 없고 필수 런타임 환경변수도 없습니다. 소개 데이터는 `data/`, Nuxt 빌드 설정은 `nuxt.config.ts`가 기준입니다.
