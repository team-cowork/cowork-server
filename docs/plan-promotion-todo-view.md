# Promotion TODO 공개 뷰 구현 명세

## 문서 상태

- 대상 브랜치: `update/promotion-site`
- 기준: PR `#310`의 head 위에 쌓인 작업
- 대상 모듈: `cowork-promotion`
- 콘텐츠 원본: `docs/todo/`
- 상태: 구현 완료 — 사용자 요청에 따라 별도 브라우저 QA 생략

## 1. 목표

프로모션 사이트에서 서버의 현재 개발 과제와 과거 점검 기록을 공개적으로 읽을 수 있게 한다.
기능을 많이 드러내는 대시보드가 아니라, 기존 cowork 디자인 시스템 안에서 문서 자체가 가장 잘
보이는 정적인 읽기 경험을 만든다.

이번 작업에서 가장 중요한 품질 기준은 다음 순서다.

1. 여백, 타이포그래피, 행의 밀도, 모달의 비례가 만드는 시각적 완성도
2. 의미가 명확한 HTML 구조와 키보드 접근성
3. `docs/todo/`를 정확하고 안전하게 변환하는 빌드 파이프라인
4. 검색, 고유 URL, 브라우저 히스토리 같은 상호작용

## 2. 핵심 원칙

### 2.1 기존 디자인 시스템을 출발점으로 삼는다

- 기존 Inter 서체, 흰색·회색 표면, `#EF4444` cowork red, `max-w-*` 컨테이너,
  spacing scale, radius, shadow, 150ms 전환을 그대로 사용한다.
- 새로운 폰트, 새로운 브랜드 색상 체계, 별도 아이콘 세트, 독립적인 디자인 토큰 체계를 만들지
  않는다.
- 가능한 값은 기존 scale에서 고른다. 문서 읽기 폭, 작은 글자에서도 대비되는 priority semantic
  color, modal의 180ms motion만 `todo.css` 안의 제한된 예외로 둔다.
- 현재 navigation의 반투명 표면은 유지하지만 TODO 화면에 새로운 glass, gradient, glow를
  추가하지 않는다.
- Dia의 시각 표현을 복제하지 않는다. 익숙한 검색 입력과 단정한 표면을 조금 더 정제한다는
  원칙만 적용한다.

### 2.2 문서에 없는 말을 UI가 만들어내지 않는다

- 페이지 상단에는 `개발 진행 현황`이라는 제목과 검색 입력만 둔다.
- 설명 문단, 통계 카드, 결과 개수, 검색 범위 안내, 키보드 shortcut badge를 넣지 않는다.
- 진행 항목 행에는 문서 제목과 우선순위만 노출한다.
- 서비스와 범주는 검색에는 사용하지만 목록의 회색 보조 텍스트로 표시하지 않는다.
- 보안 항목을 포함한 원문 전체를 공개하며 경고 또는 면책 안내를 추가하지 않는다.

### 2.3 장식을 정보 구조로 위장하지 않는다

- `08 / SECURITY` 같은 디렉터리 번호·영문 범주 표기를 화면에 노출하지 않는다.
- 목록을 카드 grid로 만들지 않는다.
- 우선순위는 배경색이 있는 chip이나 badge가 아니라 색이 들어간 텍스트로만 표현한다.
- 의미 없는 eyebrow, 반복 아이콘, 장식용 pill, 과한 hover 이동을 사용하지 않는다.

## 3. 범위

### 포함

- 홈 hero의 `레포지토리 보기` 버튼을 `개발 진행 현황 보기`로 교체
- `/todo` 진행 현황 화면
- 활성 TODO 즉시 검색
- 진행 항목과 점검 스냅샷의 고유 URL
- 거의 전체 화면을 사용하는 문서 모달
- GFM Markdown 렌더링, 문서 목차, 내부 링크 이동
- 정적 route 생성, 개발 서버 감시, CI 변경 감지
- 빌드 단위 테스트와 데스크톱·모바일 브라우저 검증

### 제외

- TODO 생성·수정·완료 처리 UI
- 우선순위 또는 서비스 filter
- 서버 API, 데이터베이스, 런타임 Markdown 요청
- 댓글, 인증, 조회 수, 공유 버튼
- 별도 시안 또는 프로토타입 산출물
- syntax highlighting
- TODO navigation 메뉴 추가
- `docs/todo/` 문서 형식 자체의 전면 개편 또는 frontmatter 도입

## 4. 정보 구조와 URL

### 4.1 공개 URL

| 화면 | URL | 빌드 결과 |
|---|---|---|
| 진행 현황 | `/todo` | `public/todo/index.html` |
| 진행 항목 | `/todo/items/{번호-범주}/{slug}` | 해당 경로의 `index.html` |
| 점검 기록 | `/todo/history/{YYYYMMDD}` | 해당 경로의 `index.html` |

예시:

```text
/todo
/todo/items/08-security/config-server-access-control
/todo/history/20260828
```

번호와 범주는 안정적인 원본 경로를 보존하기 위해 URL에는 남지만 화면에는 표시하지 않는다.

### 4.2 정적 route를 선택하는 이유

모든 상세 URL에 실제 `index.html`을 생성한다. Vercel rewrite나 개발 서버의 SPA fallback에
의존하지 않는다.

- 직접 URL 진입과 새로고침이 정적 서버에서도 동작한다.
- 각 상세 문서에 맞는 `<title>`과 Open Graph title을 빌드할 수 있다.
- 모달을 열기 위한 런타임 fetch가 필요 없다.
- 현재 promotion의 self-contained HTML 빌드 방식을 유지한다.

각 route에는 동일한 TODO shell, 정제된 문서 데이터, TODO 전용 CSS와 JavaScript를 인라인한다.
현재 Markdown 원문은 31개, 약 162KB이므로 중복 생성되는 전체 배포 크기는 허용 가능한 범위다.

- index title: `개발 진행 현황 · cowork`
- item/snapshot title: `{문서 제목} · cowork`
- 정적 `<title>`과 `og:title`은 각 물리 route의 initial document에 맞춘다.

## 5. 사용자 흐름

### 5.1 홈에서 진입

- 기존 hero의 첫 버튼 `GitHub 조직 바로가기`는 그대로 둔다.
- 두 번째 버튼의 문구를 `개발 진행 현황 보기`로 바꾸고 `href="/todo"`를 사용한다.
- 상단 navigation에 TODO 링크를 추가하지 않는다.
- navigation의 cowork logo와 wordmark 전체를 `/` 링크로 바꾼다.
- 우측 GitHub 링크는 유지한다.

### 5.2 진행 현황에서 검색

- `/todo` 진입 시 `docs/todo/README.md`의 `## 진행 중`에 등록된 항목만 보인다.
- 검색은 입력 즉시 적용된다.
- 결과 행은 우선순위 순으로 정렬되고 같은 우선순위 안에서는 README 순서를 유지한다.
- 검색 결과가 없을 때는 목록 위치에 `검색 결과가 없습니다.` 한 문장만 표시한다.
- 검색어를 지우면 전체 활성 목록으로 즉시 돌아온다.

### 5.3 상세 문서 열기와 닫기

- 항목 또는 점검 기록을 선택하면 `history.pushState`로 고유 URL을 만들고 모달을 연다.
- 목록에서 연 모달은 브라우저 Back, `Esc`, `목록으로 돌아가기`로 이전 목록 상태에 돌아간다.
- 상세 URL로 직접 진입했을 때 `Esc` 또는 `목록으로 돌아가기`를 누르면 `/todo`로 대체 이동한다.
- 모달 backdrop을 클릭해도 닫히지 않는다.
- `×` close icon은 두지 않는다.
- 모달 안에서 다른 TODO 문서 링크를 선택하면 현재 상세 history entry를 교체한다. 따라서
  `Esc`와 Back은 중간 문서를 역순으로 순회하지 않고 목록으로 돌아간다.

## 6. 화면 명세

### 6.1 공통 navigation

- 현재 높이 `4rem`, `max-w-6xl`, 좌우 `1.5rem`, 흰색 반투명 배경, 얇은 하단 border를 유지한다.
- logo 링크에는 시각적으로 중복되지 않는 `aria-label="cowork 홈"`을 제공한다.
- `/todo`에서도 동일한 navigation을 사용한다.
- 별도의 active navigation indicator는 만들지 않는다.

### 6.2 `/todo` 상단

- `<main>`의 콘텐츠 폭은 기존 `max-w-4xl`인 `56rem`을 기준으로 한다.
- 고정 navigation 아래에 충분한 상단 여백을 두고, hero처럼 중앙 정렬하지 않는다.
- 데스크톱 기준 제목 시작점은 viewport 상단에서 약 `9rem` 지점으로 잡는다.
- 제목은 `개발 진행 현황` 하나만 사용한다.
- 제목과 검색 사이, 검색과 목록 사이의 간격을 넉넉하게 두되 별도 설명으로 채우지 않는다.
- 모바일에서는 좌우 `1.25rem`, 데스크톱에서는 기존 `1.5rem` gutter를 사용한다.

권장 비례:

| 요소 | 데스크톱 | 모바일 |
|---|---:|---:|
| H1 크기 | `3rem`, 기존 bold 계열 | `2.25rem`, 기존 bold 계열 |
| H1 letter spacing | 기존 `tracking-tight` 수준 | 동일 |
| 검색 높이 | `3.5rem` | `3.25rem` |
| 상단 콘텐츠 폭 | 최대 `56rem` | viewport - `2.5rem` |
| 목록 행 세로 padding | `1.25rem` | `1rem` |

수치는 새 scale을 만들기 위한 값이 아니라 기존 promotion scale에서 사용할 기준이다. 브라우저
검증에서 한 단계 이내로 조정할 수 있지만, 화면 밀도를 높이기 위해 설명이나 추가 제어를 넣지는
않는다.

### 6.3 검색 입력

- `<form role="search">` 안에 실제 `<label>`을 두고 시각적으로만 숨긴다.
- 입력 placeholder는 `진행 중인 작업 검색`으로 한다.
- 왼쪽에 단순한 search SVG 하나만 둔다.
- 배경은 흰색, border는 기존 gray-200, radius는 기존 `0.75rem`, shadow는 `shadow-sm` 수준이다.
- focus에서는 cowork red outline 또는 ring을 사용하고 layout이 움직이지 않게 한다.
- clear 버튼, shortcut badge, helper text, 결과 개수, 검색 범위 설명을 추가하지 않는다.
- rounded input은 독립적인 큰 카드가 아니라 입력 컨트롤 한 개로 읽혀야 한다.

### 6.4 진행 항목 목록

- `<ol>` 또는 `<ul>`과 `<li>`를 사용하고 각 행 전체를 `<a>`로 만든다.
- 목록 자체에 외곽 카드 border나 shadow를 두지 않는다.
- 행 사이는 `gray-200` 계열의 1px divider로 구분한다.
- 행에는 제목과 우선순위 텍스트만 둔다.
- 제목은 왼쪽, 우선순위는 오른쪽에 놓고 priority 영역의 폭을 고정해 행의 수직 리듬을 맞춘다.
- hover와 `:focus-visible`에서만 `gray-50` 표면이 아주 약하게 드러난다.
- hover 시 translate, scale, 큰 shadow를 적용하지 않는다.
- 긴 제목은 임의로 한 줄 말줄임하지 않고 자연스럽게 줄바꿈한다.

우선순위 표현:

| 값 | 표시 | 색상 | 정렬 |
|---|---|---|---:|
| `high` | `높음` | red `#DC2626` | 1 |
| `medium` | `중간` | amber `#B45309` | 2 |
| `low` | `낮음` | 차분한 green `#15803D` | 3 |
| `unknown` | `미지정` | gray `#4B5563` | 4 |

텍스트가 이미 `높음`, `중간`, `낮음`이라는 의미를 전달하므로 색상만으로 우선순위를 구분하지
않는다. 색상은 작은 row text에서도 WCAG AA 대비를 만족하는 짙은 단계로 사용하고, 브랜드 accent와
focus에는 기존 cowork red `#EF4444`를 유지한다. 현재 원본에 낮음 항목이 없어도 렌더러와 테스트는
반드시 지원한다.

### 6.5 점검 기록

- 활성 목록이 끝난 뒤 충분한 수직 여백을 두고 `점검 기록` section을 배치한다.
- 별도 카드나 timeline 장식 없이 같은 divider 기반 list 문법을 사용한다.
- README 링크의 날짜를 `2026.08.28` 형식으로 표시하고, README에 이미 있는 점검 설명과
  `2026.08.28 — 서버 의존성·애플리케이션 코드 점검`처럼 같은 행의 주 정보로 표현한다. 설명을
  작은 회색 부제목으로 분리하지 않는다.
- 날짜는 장식용 번호가 아니라 기록 식별자이므로 범주 label처럼 변환하지 않는다.
- 점검 기록은 검색 대상에 포함하지 않는다.

### 6.6 문서 모달

native `<dialog>`를 사용하고 JavaScript에서 `showModal()`로 연다.

데스크톱:

- viewport 사방에 `1.5rem`에서 `2rem` 정도의 여백만 남기는 near-fullscreen surface
- 흰색 배경, 기존 `1.5rem` radius, `shadow-2xl` 수준의 그림자
- backdrop은 `gray-900` 기반의 약 70% 불투명도
- 모달 내부만 스크롤하며 body scroll은 잠근다.
- 상단 왼쪽에 아이콘 없는 text button `목록으로 돌아가기`를 둔다.
- 읽기 본문은 약 `44rem` 폭으로 제한한다.
- 본문은 modal 전체를 기준으로 시각적 중앙에 둔다. 같은 폭의 좌우 rail을 만들고 오른쪽 rail에
  고정 폭 목차를 sticky로 배치한다. 전체 모달이 넓어도 본문 줄 길이는 늘리지 않는다.

모바일과 tablet portrait:

- `width: 100vw`, `height: 100dvh`, margin `0`, radius `0`
- 별도 backdrop 여백이 보이지 않는 진짜 full-screen surface
- 목차는 숨긴다. desktop layout은 `1025px`부터 적용하고 `1024px` 이하는 full-screen/no-TOC로
  처리한다.
- 되돌아가기 영역은 상단 safe area를 고려하고 스크롤 중에도 접근 가능하게 한다.
- 표와 code block만 가로 스크롤되며 문서 전체에는 가로 스크롤이 생기지 않는다.

닫기 규칙:

- `Esc`: 닫기
- `목록으로 돌아가기`: 닫기
- 브라우저 Back: 닫기 또는 이전 실제 페이지로 이동
- backdrop click: 아무 동작 없음
- `×` 버튼: 없음

### 6.7 모달 문서 헤더

Markdown의 첫 H1과 그 뒤 첫 H2 전에 있는 metadata bullet list를 본문에서 분리해 semantic header로
재구성한다.

```html
<header class="todo-document__header">
  <h1 id="todo-document-title">문서 제목</h1>
  <dl class="todo-document__metadata">
    <div>
      <dt>서비스</dt>
      <dd>...</dd>
    </div>
    <div>
      <dt>우선순위</dt>
      <dd>높음</dd>
    </div>
  </dl>
</header>
```

- metadata label을 고정된 소수 목록으로 제한하지 않고 원본 순서대로 보존한다.
- `서비스`, `우선순위`, `현재 상태`, `결론`, `관련 작업` 등 현재 문서의 차이를 허용한다.
- `<dl>`은 배경, 외곽 border, chip이 없는 평문 label/value 행으로 렌더한다. 짧은 label 열과
  유연한 value 열만 두고 card나 2열 통계 grid로 만들지 않는다.
- metadata가 없는 snapshot은 H1 다음부터 본문을 그대로 시작한다.
- header에 범주, 디렉터리 번호, 읽기 시간, 자동 요약을 새로 만들지 않는다.

### 6.8 문서 본문 타이포그래피

- 본문 기본 크기는 `1rem`에서 `1.0625rem`, line-height는 약 `1.75`를 기준으로 한다.
- H2/H3의 위쪽 간격을 아래쪽 간격보다 크게 해 section 경계를 여백으로 표현한다.
- paragraph, list, table, blockquote, code의 간격을 한 가지 vertical rhythm으로 맞춘다.
- 링크는 cowork red 또는 진한 본문색과 underline을 함께 사용한다.
- inline code는 아주 옅은 gray surface와 기존 small radius를 사용한다.
- fenced code는 syntax highlighting 없이 언어 class만 보존하고 수평 스크롤을 허용한다.
- table은 작은 화면에서 table wrapper만 수평 스크롤되도록 한다.
- blockquote는 중립 gray border와 본문색을 사용한다. 새로운 accent panel로 만들지 않는다.
- 현재 corpus에는 이미지가 없다. asset copy/rewrite가 이번 범위에 없으므로 image node가 추가되면
  경로 존재 여부와 무관하게 지원되지 않는 입력이라는 명확한 빌드 오류를 낸다.

### 6.9 데스크톱 목차

- H2와 H3만 포함한다.
- `nav aria-label="문서 목차"`를 사용한다.
- 시각 label은 `목차` 한 단어만 사용한다.
- 각 항목은 문서 heading anchor로 이동한다.
- 별도 번호, 진행 bar, active-section animation은 넣지 않는다.
- heading이 없으면 목차 열 자체를 렌더하지 않는다.
- `1024px` 이하에서는 `display: none` 또는 동등한 방식으로 navigation 전체를 접근성 tree와
  focus 순서에서도 제외한다.

### 6.10 motion

- backdrop opacity와 surface의 아주 작은 이동만 사용한다.
- duration은 약 `180ms`로 통일한다.
- surface 시작점은 `translateY(6px)`와 `scale(0.995)`를 넘지 않는다.
- elastic/spring, stagger, 반복 애니메이션을 사용하지 않는다.
- `prefers-reduced-motion: reduce`에서는 transform을 제거하고 즉시 상태를 전환한다.

## 7. 의미 구조

TODO shell은 다음 구조를 기준으로 한다.

```html
<body class="todo-page">
  <div class="min-h-screen bg-white font-sans text-gray-900 antialiased">
    <nav>...</nav>
    <main id="main-content">
      <header>
        <h1>개발 진행 현황</h1>
        <form role="search">...</form>
      </header>
      <section aria-label="진행 중인 작업">
        <ol>...</ol>
        <p role="status" hidden>검색 결과가 없습니다.</p>
        <p class="sr-only" aria-live="polite">...</p>
      </section>
      <section aria-labelledby="todo-history-title">
        <h2 id="todo-history-title">점검 기록</h2>
        <ol>...</ol>
      </section>
    </main>
    <dialog aria-labelledby="todo-document-title">...</dialog>
  </div>
</body>
```

필수 마크업 규칙:

- dashboard와 문서는 각각 자신의 범위에서 H1 하나를 가진다. dialog가 닫힌 동안 문서 H1은
  접근성 tree에서 제외되고, 열린 동안 dialog 바깥 dashboard는 inert가 된다.
- clickable `<div>` 대신 실제 anchor와 button을 사용한다.
- 모든 item과 snapshot 행은 canonical 상세 URL을 가진 실제 `href`를 사용한다. JavaScript는
  수정 키가 없는 주 버튼 click만 가로채며 새 탭, 새 창, context menu 동작을 보존한다.
- 검색 label을 placeholder로 대체하지 않는다.
- 장식 SVG에는 `aria-hidden="true"`를 사용한다.
- list가 검색으로 바뀌어도 항목 DOM 순서와 focus 순서가 일치해야 한다.
- 화면에는 결과 개수를 추가하지 않지만 검색 변경은 visually-hidden `aria-live="polite"` 영역에서
  짧게 알리고, 결과 없음 문구는 visible `role="status"`로도 전달한다.

## 8. 콘텐츠 수집 규칙

`docs/todo/README.md`를 활성 상태의 단일 기준으로 삼는다.

### 8.1 활성 항목

- `## 진행 중` section의 Markdown 링크를 원본 순서대로 수집한다.
- 취소선 안의 링크는 완료된 항목으로 보고 활성 목록에서 제외한다.
- 링크 대상이 없거나 같은 route가 두 번 등록되면 빌드를 실패시킨다.
- README의 링크 title과 상세 문서 H1이 다르면 빌드를 실패시킨다.
- `items/**/*.md` 중 README에 없는 문서는 목록에서 숨기되 route는 생성한다. 과거 snapshot의
  내부 링크가 계속 열려야 하기 때문이다.

### 8.2 점검 기록

- `## 점검 스냅샷`의 링크를 수집한다.
- 파일명 `{YYYYMMDD}_TODO.md`에서 route 날짜를 만든다.
- 표시용 날짜는 `YYYY.MM.DD`로 변환한다.
- README에 있는 설명은 새로 요약하지 않고 그대로 사용한다.

### 8.3 상세 metadata

- 첫 H1 뒤부터 첫 H2 전까지의 첫 bullet list를 metadata로 본다.
- 각 항목의 첫 strong text와 뒤따르는 colon을 label로, 나머지를 value로 추출한다.
- 우선순위는 emoji가 아니라 `높음`, `중간`, `낮음` 텍스트로 판별한다.
- 알 수 없는 값은 `unknown`으로 보존하고 경고 후 계속한다.
- 서비스가 없거나 현재 상태 대신 결론이 있어도 경고 없이 렌더 가능한 best-effort 구조를 유지한다.

## 9. 내부 데이터 모델

```js
{
  id: "items/08-security/config-server-access-control",
  kind: "item", // item | snapshot
  sourcePath: "items/08-security/config-server-access-control.md",
  route: "/todo/items/08-security/config-server-access-control",
  title: "Config Server 접근 보호",
  category: "security",
  priority: "high", // high | medium | low | unknown
  priorityLabel: "높음",
  metadata: [{ label: "서비스", text: "...", html: "..." }],
  searchText: "...",
  bodyHtml: "...",
  toc: [{ id: "문제", depth: 2, text: "문제" }],
  sourceOrder: 0,
  active: true
}
```

- `id`는 숫자만 사용하지 않고 `docs/todo` 상대 경로를 기반으로 한다.
- 검색용 plain text와 렌더링용 sanitized HTML을 분리한다.
- inline JSON은 기존 `inlineJson()`과 동일하게 `<`, script 종료 문자열, Unicode separator를 안전하게
  escape한다.

## 10. Markdown 변환

build-time AST 파이프라인으로 `unified` 생태계를 사용한다.

필요한 devDependencies:

- `unified@11`
- `remark-parse@11`
- `remark-gfm@4`
- `remark-rehype@11`
- `rehype-sanitize@6`
- `rehype-slug@6`
- `rehype-stringify@10`
- `unist-util-visit@5`
- `mdast-util-to-string@4`

변환 순서:

1. `remark-parse`
2. `remark-gfm`
3. H1과 상단 metadata 위치를 식별하되 link rewrite 전에는 하위 AST를 버리지 않음
4. 전체 Markdown AST의 link를 검증하고 route로 rewrite
5. H1, metadata, body를 분리하고 metadata와 body 모두 같은 안전 변환 경로에 투입
6. `remark-rehype` — dangerous HTML 비활성
7. `rehype-slug`
8. `rehype-sanitize` — 생성된 ID까지 검사하고 DOM clobber 방지 prefix 적용
9. 신뢰하는 자체 transform으로 최종 heading ID 기반 TOC와 fragment link를 맞추고 table wrapper 생성
10. 외부 링크에 고정된 안전 속성 보강
11. `rehype-stringify`

원본 raw HTML은 실행하거나 통과시키지 않고 제거한다.

sanitize는 안전하지 않은 변환 중 가장 마지막에 둔다. `rehype-slug`가 생성한 `id`도 sanitizer를
통과시키고, sanitizer의 `clobberPrefix`를 `todo-`로 지정한다. 그 뒤에는 프로젝트가 소유한 고정
transform만 실행한다. 이 transform은 sanitizer가 확정한 실제 heading ID를 읽어 TOC를 만들고,
문서 안의 fragment href에도 같은 prefix를 적용한다.

schema는 default schema에서 필요한 것만 확장한다.

- GFM task list가 생성하는 disabled `input[type="checkbox"]`, `checked`, `disabled`
- task list에 필요한 제한된 class
- fenced code의 `language-*` class
- table wrapper는 sanitizer 이후 프로젝트 코드가 생성하는 고정 `div`와 class만 사용

### 10.1 링크 rewrite

| 원본 | 결과 |
|---|---|
| `./items/08-security/a.md` | `/todo/items/08-security/a` |
| `../reliability/a.md` | 해당 item route |
| `./20260828_TODO.md` | `/todo/history/20260828` |
| `./items/` | `/todo` |
| `#완료-조건` | 현재 문서의 clobber-safe heading anchor |
| `https://...` | 새 탭, `rel="noopener noreferrer"` |
| `mailto:...` | 그대로 허용 |

- `http`, `https`, `mailto` 외 scheme은 거부하거나 sanitize로 제거한다.
- 정규화한 상대 경로가 `docs/todo` 밖으로 나가면 빌드를 실패시킨다.
- 등록되지 않은 내부 Markdown 대상은 빌드를 실패시킨다.
- image node는 이번 범위에서 모두 빌드를 실패시킨다.
- 중복 heading slug는 `-1`, `-2` 식으로 안정적으로 구분한다.

## 11. 검색

검색 대상은 활성 item뿐이다.

- index field: title, 서비스 metadata plain text, category
- 화면 표시: title, priority만
- normalization: Unicode `NFKC` → lowercase → 연속 공백 정리 → trim
- query를 공백 단위 token으로 나누고 모든 token이 포함되는 AND 검색
- 입력 event마다 즉시 filter
- 대소문자와 한글 자모 호환 범위에서 브라우저 기본 문자열 동작을 사용한다.
- 결과를 검색 점수로 재정렬하지 않고 기존 priority/source 순서를 유지한다.
- 검색어는 `/todo?q=...`에 `history.replaceState`로 반영한다. 입력마다 history entry를 추가하지 않는다.
- 상세를 열 때는 깨끗한 상세 path를 사용하며 Back으로 돌아왔을 때 이전 `/todo?q=...`가 복구된다.
- form은 `action="/todo"`, `method="get"`, input `name="q"`를 가져 기본 HTML 의미를 보존한다.
  즉시 filtering은 JavaScript enhancement이며, JavaScript가 없을 때는 문서 링크와 읽기 기능을
  우선 보장한다.

## 12. 모달과 history 상태

pathname, query, hash를 최종 상태의 기준으로 삼고 `event.state`만 신뢰하지 않는다.

### 12.1 목록에서 열기

1. opener anchor를 저장한다.
2. 상세 path를 `pushState`한다.
3. 해당 문서 HTML을 dialog에 배치한다.
4. `showModal()`을 호출하고 body scroll을 잠근다.
5. `목록으로 돌아가기` button에 초기 focus를 둔다.

### 12.2 닫기

- 현재 entry가 TODO shell에서 만든 modal entry면 `history.back()`을 사용한다.
- 직접 상세 URL로 진입한 경우 `replaceState`로 `/todo`를 만든 뒤 dialog를 닫는다.
- 일반 진입에서 닫은 뒤 focus를 원래 행 anchor로 복원한다.
- 직접 진입에서 닫은 뒤 검색 input 또는 페이지 H1에 focus를 둔다.
- 닫기 animation이 끝날 때 `dialog.close()`와 body scroll 복원을 수행한다.
- `cancel` event는 `preventDefault()`한 뒤 동일한 닫기 함수를 호출한다.
- TOC와 본문 내 same-document fragment는 기본 hash history를 만들지 않고 `replaceState` 후 modal
  scroll container 안의 heading으로 이동한다. 따라서 Back 한 번이 heading 순회가 아니라 modal
  닫기로 이어진다.
- 내부 링크의 목적지가 `/todo`이면 문서 교체가 아니라 동일한 modal close 경로를 사용한다.
- client-side 문서 교체와 modal 종료 때 `<title>`과 Open Graph title을 현재 route에 맞게 갱신한다.

### 12.3 초기 상세 route

- 상세 정적 HTML은 dashboard를 `hidden` 처리하고 선택된 문서를 `<dialog open>`에 서버 렌더한다.
  JavaScript가 없어도 이 non-modal fallback surface에서 문서를 읽고 링크를 따라갈 수 있어야 한다.
- bootstrap은 초기 `open` attribute를 제거한 뒤 같은 dialog node에 `showModal()`을 호출하고,
  dashboard의 `hidden`을 해제해 backdrop 뒤의 목록 상태를 복원한다.
- 이 승격은 첫 meaningful paint 전에 끝나야 하며 빈 dialog나 dashboard만 잠깐 보이는 flash가 없어야
  한다.
- `/todo` index HTML은 dashboard를 보이고 dialog는 닫힌 상태로 출력한다.

## 13. 접근성

- native `<dialog>`와 `showModal()`의 focus trap을 사용한다.
- dialog는 `aria-labelledby`로 문서 H1과 연결한다.
- `Esc`, browser Back, 명시적 되돌아가기 모두 같은 close 경로를 사용한다.
- backdrop click은 close로 연결하지 않는다.
- 모든 interactive element에 명확한 `:focus-visible` 상태를 둔다.
- focus ring은 cowork red를 사용하되 대비를 위해 white offset 또는 충분한 두께를 둔다.
- priority는 색상뿐 아니라 한글 텍스트로도 표현한다.
- modal open 동안 배경은 inert 처리되고 screen reader focus가 빠져나가지 않아야 한다.
- 직접 상세 route의 `<dialog open>` fallback에서는 dashboard를 `hidden` 처리해 동시에 두 view가
  읽히지 않게 한다. `showModal()` 승격 후에는 브라우저의 modal inert 처리를 사용한다.
- 200% zoom에서 본문과 control이 겹치거나 잘리지 않아야 한다.
- `prefers-reduced-motion`을 존중한다.
- 모바일 safe-area inset을 고려한다.

## 14. 보안과 콘텐츠 안전

원문은 공개하지만 원문 안의 실행 가능한 markup을 신뢰하지 않는다.

- raw HTML은 기본적으로 버린다.
- `script`, event handler, inline style, iframe을 허용하지 않는다.
- `javascript:`, `data:` 등 위험 URL을 허용하지 않는다.
- external link에는 `noopener noreferrer`를 적용한다.
- inline JSON의 `</script>` 탈출을 차단한다.
- path traversal과 `docs/todo` 바깥 상대 참조를 빌드 오류로 처리한다.
- 공개 경고 문구는 추가하지 않는다.

## 15. 빌드 구조

### 15.1 home과 TODO bundle 분리

- 기존 `public/index.html`은 현재 home JS/CSS만 포함한다.
- TODO route는 TODO 전용 JS와 기존 base/utilities/component 스타일 중 필요한 것, `todo.css`만 포함한다.
- TODO를 위해 home의 showcase state나 외부 repository graph 데이터를 싣지 않는다.
- logo SVG는 기존처럼 data URL로 인라인한다.

### 15.2 생성 순서

1. home data와 TODO Markdown을 병렬로 읽는다.
2. home HTML을 기존 방식으로 생성한다.
3. TODO registry와 전체 문서 model을 생성한다.
4. TODO shell, CSS, JS, inline JSON을 한 번 bundle한다.
5. `/todo` index를 출력한다.
6. item과 snapshot마다 같은 shell에 route별 title과 initial document를 주입해 `index.html`을 출력한다.
7. 전체 출력이 성공한 뒤 build summary를 기록한다.

`build()`는 고정 전역 경로만 사용하지 않고 최소한 `outputDirectory`와 `todoDirectory`를 주입할 수
있게 한다. production 기본값은 현재 `public/`과 `../docs/todo/`이며 test는 `mkdtemp`로 만든 output과
fixture directory를 사용한다. Node test의 병렬 실행이 실제 `public/`을 지우거나 서로의 결과와
경쟁해서는 안 된다.

### 15.3 개발 서버

- module root watcher와 `../docs/todo` watcher를 별도로 둔다.
- 두 watcher가 같은 debounce/rebuild queue를 사용한다.
- Markdown 저장 시 TODO route 전체를 다시 만들고 SSE reload를 보낸다.
- shutdown에서 두 watcher를 모두 닫는다.
- 물리 디렉터리 route이므로 별도 SPA fallback은 추가하지 않는다.

## 16. 파일 변경 계획

### 추가

| 파일 | 책임 |
|---|---|
| `cowork-promotion/scripts/lib/markdown.mjs` | GFM AST 변환, sanitize, link rewrite, heading/TOC |
| `cowork-promotion/scripts/lib/todo-content.mjs` | README registry, discovery, metadata, sort, validation |
| `cowork-promotion/scripts/lib/todo-render.mjs` | dashboard row, history row, route별 shell 렌더링 |
| `cowork-promotion/src/html/todo.html` | TODO application shell |
| `cowork-promotion/src/html/sections/todo-dashboard.html` | H1, 검색, 활성 목록, 점검 기록 markup |
| `cowork-promotion/src/html/components/todo-dialog.html` | dialog, back control, TOC, article outlet markup |
| `cowork-promotion/src/css/todo.css` | TODO 화면과 문서 typography, modal, responsive 규칙 |
| `cowork-promotion/src/js/todo-main.js` | TODO app bootstrap |
| `cowork-promotion/src/js/core/todo-router.js` | pathname, query, history, direct-entry 처리 |
| `cowork-promotion/src/js/components/todo-dashboard.js` | 검색과 목록 상태 |
| `cowork-promotion/src/js/components/todo-dialog.js` | dialog, focus, scroll lock, close motion |
| `cowork-promotion/src/js/data/load-todos.js` | inline TODO JSON 로드와 검증 |
| `cowork-promotion/test/fixtures/todo/**` | low/unknown, GFM, unsafe markup, irregular metadata fixture |
| `cowork-promotion/test/todo-content.test.mjs` | registry, metadata, sort, 오류 정책 |
| `cowork-promotion/test/todo-markdown.test.mjs` | GFM, sanitize, heading, link rewrite |
| `cowork-promotion/test/todo-routing.test.mjs` | URL과 search normalization pure logic |
| `cowork-promotion/test/build.test.mjs` | 실제 route 산출물과 self-contained 결과 |

### 변경

| 파일 | 변경 |
|---|---|
| `cowork-promotion/scripts/build.mjs` | 경로 주입 가능한 home/TODO bundle과 다중 정적 route 생성 |
| `cowork-promotion/scripts/serve.mjs` | `docs/todo` watcher 추가 |
| `cowork-promotion/scripts/lib/bundle.mjs` | 필요 시 page별 bundle helper를 일반화 |
| `cowork-promotion/src/html/components/head.html` | route별 title/description/OG marker 지원 |
| `cowork-promotion/src/html/components/navigation.html` | logo를 `/` anchor로 변경 |
| `cowork-promotion/src/html/sections/hero.html` | 두 번째 CTA를 `/todo`로 교체 |
| `cowork-promotion/package.json` | `node --test` script와 Markdown 의존성 |
| `cowork-promotion/package-lock.json` | lockfile 갱신 |
| `cowork-promotion/README.md` | 콘텐츠 원본, route, build 방식 설명 |
| `.github/workflows/cowork-stage-ci.yml` | `docs/todo/**`를 promotion filter에 포함하고 누락된 일반 promotion matrix 분기 추가 |
| `.github/workflows/cowork-prod-ci.yml` | `docs/todo/**`를 promotion filter에 포함 |

`cowork-promotion/vercel.json`은 물리 route를 생성하므로 rewrite 변경이 필요 없다.

## 17. 오류 정책

### 빌드 실패

- README의 `진행 중` 또는 `점검 스냅샷` 필수 section 누락
- 활성 링크 대상 누락 또는 중복
- 활성 link title과 상세 H1 불일치
- 상세 문서 H1 누락
- route 충돌
- `docs/todo` 밖으로 나가는 상대 경로
- 존재하지 않는 내부 Markdown 링크
- image node 발견 — 이번 범위에서는 asset copy/rewrite를 지원하지 않음
- 처리되지 않은 build/template marker

### 경고 후 계속

- 선택 metadata 누락
- 알 수 없는 우선순위: `unknown`으로 마지막 정렬
- README에 없는 item 문서: 목록에서는 숨기고 route는 생성
- raw HTML: 제거

## 18. 구현 순서

별도 시안은 만들지 않고 production template에서 실제 Markdown 일부를 사용해 바로 조정한다.

### 1단계 — semantic markup과 시각 골격

1. `todo.html`, dashboard, dialog partial을 만든다.
2. 기존 navigation 재사용과 home CTA 교체를 적용한다.
3. 실제 제목 몇 개를 임시로 렌더해 page width, 상단 여백, 행 높이, priority text 위치를 맞춘다.
4. dialog에 실제 긴 문서와 table을 넣어 reading width, 목차, 모바일 full-screen을 먼저 완성한다.
5. 이 단계에서 카드화, 보조 문구, 과도한 badge가 생기지 않았는지 브라우저로 확인한다.

### 2단계 — Markdown build pipeline

1. README registry parser를 구현한다.
2. item/snapshot discovery와 model 생성을 구현한다.
3. AST metadata 분리, sanitize, link rewrite, TOC를 구현한다.
4. 오류 정책을 unit test로 고정한다.

### 3단계 — 정적 route와 상호작용

1. 모든 물리 route를 출력한다.
2. search를 연결한다.
3. dialog와 history router를 연결한다.
4. direct-entry, focus restore, body scroll, reduced motion을 검증한다.

### 4단계 — 자동화와 배포 경계

1. Node test와 build output test를 추가한다.
2. 개발 watcher에 `docs/todo`를 추가한다.
3. stage/prod CI path filter를 수정한다.
4. Vercel project의 `Include source files outside Root Directory in the Build Step`을 활성화한다.
5. Vercel Git Integration의 Ignored Build Step이 `docs/todo/**` 변경을 무시하지 않게 설정한다.
6. docs-only 변경으로 preview deployment가 생기는지 확인한다.

### 5단계 — 실제 화면 다듬기

1. desktop과 mobile screenshot을 나란히 확인한다.
2. 제목·검색·첫 행 사이의 여백과 modal reading width를 우선 조정한다.
3. 줄바꿈이 긴 실제 한국어 제목, 긴 metadata, table, code block을 확인한다.
4. 색과 장식보다 alignment, whitespace, line-height를 먼저 수정한다.
5. 합의하지 않은 설명·badge·아이콘이 추가되지 않았는지 마지막으로 제거한다.

## 19. 테스트 계획

### 19.1 Node test

`package.json`:

```json
{
  "scripts": {
    "test": "node --test"
  }
}
```

필수 test case:

- README 활성 항목과 snapshot 추출
- 취소선 항목 제외
- high → medium → low → unknown stable sort
- 실제 원본에는 없는 low priority fixture 렌더링
- irregular metadata와 metadata 없는 snapshot
- GFM table, strikethrough, task list, fenced code
- 중복 한국어 heading의 고유 slug
- 상대 TODO 링크의 route rewrite
- raw `<script>`와 inline handler 제거
- `javascript:`/`data:` URL 제거
- path traversal 차단
- inline JSON script 탈출 차단
- README link/H1 mismatch 실패
- 모든 발견 문서의 정적 `index.html` 생성
- route별 `<title>`과 initial document 일치
- unresolved include/data/bundle marker 없음
- build 결과에 개발 reload script와 raw Markdown HTML이 남지 않음
- 같은 입력으로 생성한 output hash가 동일함

### 19.2 브라우저 검증

화면 크기:

- `1440px`: 넓은 desktop, modal inset과 TOC
- `1024px`: full-screen modal, 목차 없음
- `1025px` 이상: inset modal, 오른쪽 목차 rail
- `390px`: 일반 mobile full-screen modal
- `320px`: 긴 title과 control wrapping
- 200% zoom

상호작용:

- 검색 title/service/category match와 empty state
- Tab/Shift+Tab 순서와 focus-visible
- Enter로 row 열기
- 모달 focus trap
- backdrop click 무반응
- `Esc`, 명시적 되돌아가기, browser Back
- 목록에서 연 뒤 검색어와 opener focus 복원
- direct detail URL 새로고침 후 닫기
- modal 내부 TODO link 이동 후 한 번에 목록 복귀
- table/code horizontal scroll
- body background scroll 잠금과 복원
- reduced motion
- console error 없음

검증 명령:

```bash
cd cowork-promotion
npm ci
npm test
npm run build
npm run preview
```

Vercel CLI가 연결된 환경에서는 추가로 `vercel build`를 실행한다.

## 20. 완료 조건

### 시각·마크업

- `/todo` 첫 화면에 navigation, H1, 검색, 진행 목록 외의 불필요한 UI가 없다.
- 넓은 여백 속에서도 H1, 검색, 목록의 alignment가 하나의 축으로 정돈되어 있다.
- 활성 행에는 제목과 색이 들어간 우선순위 text만 보인다.
- 서비스, 범주, directory number가 목록에 노출되지 않는다.
- 카드 grid, 통계 카드, helper copy, shortcut badge, 우선순위 chip이 없다.
- 모달 본문 줄 길이가 안정적이고 넓은 화면에서 빈 공간을 목차가 기능적으로 사용한다.
- 모바일 모달은 radius와 backdrop 여백 없는 full-screen이다.

### 기능

- `/todo`가 README의 활성 항목을 priority/source order로 표시한다.
- title, service, category 검색이 즉시 동작한다.
- item과 snapshot의 모든 고유 URL이 직접 열리고 새로고침된다.
- backdrop click을 제외한 합의된 세 가지 닫기 경로가 동작한다.
- desktop에만 H2/H3 목차가 보인다.
- GFM 원문 전체가 안전한 HTML로 읽힌다.

### 빌드·운영

- `docs/todo`만 바뀌어도 local rebuild와 stage/prod promotion CI가 실행된다.
- 깨진 내부 문서 관계가 배포 전에 build/test에서 실패한다.
- 원문 HTML이나 위험 URL이 실행되지 않는다.
- home bundle에 TODO 문서 데이터가 포함되지 않는다.
- TODO route는 runtime fetch 없이 동작한다.
- Vercel build가 Root Directory 밖의 `docs/todo`를 포함하고 docs-only commit도 preview 배포 대상으로
  인식한다.

## 21. 조사에서 반영한 판단

- [Anthropic frontend-design skill](https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md)의
  핵심은 특정 유행을 복제하는 대신 프로젝트 맥락에 맞는 명확한 미학 방향을 먼저 고정하는 것이다.
- [Anthropic의 frontend design skills 글](https://claude.com/blog/improving-frontend-design-through-skills)에서
  다루는 반복적인 기본값 회피를, 이 작업에서는 새 스타일 추가보다 불필요한 요소 제거로 해석한다.
- [Dia 공식 사이트](https://www.diabrowser.com/)와
  [The Browser Company의 Dia 디자인 설명](https://browsercompany.substack.com/p/the-strategy-behind-dias-design)에서
  익숙한 control을 유지하면서 표면과 상호작용을 정제하는 접근만 차용한다.
- [Brad Frost의 design system recipes](https://bradfrost.com/blog/post/the-art-of-design-system-recipes/)처럼
  기존 token과 component를 고정 재료가 아니라 일관된 조합 규칙으로 사용한다.
- [Vercel Web Interface Guidelines](https://github.com/vercel-labs/web-interface-guidelines)와
  [WAI-ARIA modal dialog pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)을 구현 후
  interaction/accessibility 검증 기준으로 사용한다.
- [rehype-sanitize 공식 문서](https://github.com/rehypejs/rehype-sanitize)의 권고대로 sanitizer를
  마지막 unsafe transform 뒤에 두고, heading ID의 DOM clobbering 방지까지 검증한다.

조사의 결론은 외부 디자인 skill을 더 설치하는 것이 아니라, 이미 사용한 frontend-design 지침과
기존 promotion 디자인 시스템을 기준으로 실제 markup을 만들고 브라우저에서 반복 검증하는 것이다.
