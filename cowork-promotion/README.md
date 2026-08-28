# cowork-promotion

## 역할

cowork 제품을 소개하는 바닐라 HTML 기반 정적 프로모션 웹사이트입니다. 별도 백엔드 의존성이 없습니다.

## 스택

- HTML
- CSS
- JavaScript ES modules
- self-contained HTML 프로덕션 번들
- YAML / XML / Markdown 빌드 데이터
- Server-Sent Events 기반 개발용 라이브 리로드
- npm(`package-lock.json`)

## 실행

```bash
npm install
npm run dev
```

- 개발 서버 기본 포트: `3000`
- `npm run dev`는 promotion 소스와 `../docs/todo/**/*.md` 변경을 감지해 재빌드하고 SSE로 브라우저를 새로고침합니다.
- 정적 빌드: `npm run build` (`public/`에 배포 파일 생성)
- 프로덕션 빌드/미리보기: `npm run preview`

## 소스 구조

```text
src/
├── html/
│   ├── components/    # 내비게이션, 구분선, 푸터 등 공용 partial
│   └── sections/      # Hero, Repository, Feature, Position, TODO 페이지 섹션
├── css/               # base, utility, component, animation, responsive 레이어
└── js/
    ├── core/          # 상태 저장소, 앱 생명주기, DOM/transition primitive
    ├── components/    # Feature·Position showcase 컴포넌트
    └── data/          # 상태 데이터 로더
```

HTML은 `<!-- @include ... -->` 지시문을 빌드 시 조합합니다. 클라이언트 JavaScript는 React의 단방향 상태 갱신과 컴포넌트 생명주기를 참고해 이 페이지에 필요한 `store`, `mount`, `unmount`, transition만 직접 구현했습니다. 가상 DOM이나 런타임 템플릿 엔진은 포함하지 않습니다.

## 설정 공급

현재 정적 페이지는 백엔드·Config Server·Vault 의존성이 없고 필수 런타임 환경변수도 없습니다. `src/html`, `src/css`, `src/js`가 화면 소스이며 다음 파일이 반복 콘텐츠의 단일 데이터 소스입니다.

- `data/tech-stacks.yaml`: 기술 스택 그룹과 포지션별 설명·색상·기술 목록
- `data/team-members.xml`: 팀원의 이름, GitHub 계정, 기수, 포지션, 강조 색상
- `data/feature-states.json`: 기능 소개 스크롤 장면
- `../docs/todo/README.md`: 공개 진행 목록과 점검 기록의 기준
- `../docs/todo/items/**/*.md`, `../docs/todo/*_TODO.md`: TODO 상세 문서와 점검 스냅샷

`npm run build`는 YAML/XML과 TODO Markdown을 검증하고 홈 화면 및 TODO 문서를 생성합니다. 분리된 CSS·JavaScript 모듈·상태 JSON·로고는 각 HTML에 인라인되므로 런타임 데이터 요청이 필요하지 않습니다.

주요 빌드 결과:

```text
public/index.html
public/todo/index.html
public/todo/items/{번호-범주}/{slug}/index.html
public/todo/history/{YYYYMMDD}/index.html
```

GitHub 아바타와 저장소 언어 그래프, Google Fonts처럼 외부에서 최신 상태를 공급받는 미디어는 빌드에 고정하지 않고 외부 요청으로 유지합니다.

개발 서버의 SSE 엔드포인트와 새로고침 스크립트는 `--watch` 모드의 HTML 응답에만 주입됩니다. `public/index.html`과 Vercel 배포 결과에는 개발용 코드가 들어가지 않습니다.

## 배포

Vercel은 프레임워크를 `Other`로 고정하고 `npm run build`가 생성하는 `public/`을 배포합니다. 프로젝트 Root Directory가 `cowork-promotion`이면 **Settings > Build and Deployment > Root Directory**에서 **Include source files outside Root Directory in the Build Step**을 활성화해 `docs/todo`를 빌드 입력에 포함해야 합니다. Git Integration의 Ignored Build Step도 `docs/todo/**` 변경을 배포 대상에서 제외하면 안 됩니다.

로컬에서 Vercel 설정까지 확인하려면 다음을 실행합니다.

```bash
vercel build
```
