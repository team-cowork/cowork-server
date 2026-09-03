# Promotion 디자인 시스템

기존 화면의 시각적 정체성을 유지하면서 공통 스타일과 반복 마크업을 재사용한다. 별도의 UI 프레임워크나 런타임 CSS 라이브러리는 사용하지 않는다.

## 구조

| 계층 | 파일 | 책임 |
|---|---|---|
| 기초·의미 토큰 | `src/css/tokens.css` | 팔레트, 표면·텍스트·상태 색상, 글꼴, 간격, 모서리, 그림자, 모션 |
| 공통 컴포넌트 | `src/css/ui.css` | 버튼, 배지, 아바타, 멤버 카드, 카드, 섹션 제목, 컨테이너, 포커스 |
| 정적 마크업 | `src/html/components/` | 로고, 아이콘, 내비게이션, 푸터 등 빌드 시 포함하는 HTML 조각 |
| 데이터 기반 컴포넌트 | `scripts/lib/ui.mjs` | 배지·아바타·멤버 카드·저장소 카드 생성 |
| 화면 조합 | `scripts/lib/render.mjs`, `src/html/sections/` | 공통 컴포넌트에 콘텐츠를 전달하여 화면 구성 |
| 도메인 스타일 | `src/css/todo.css`, `src/css/showcase.css` | 문서 읽기·쇼케이스에 한정된 표현 |

`build.mjs`는 홈과 TODO 문서 모두에 토큰과 공통 컴포넌트 CSS를 포함한다. 배포 형식은 기존처럼 CSS·JavaScript·데이터가 들어 있는 단일 HTML 응답이다.

## 토큰

기본 팔레트는 흰 표면 `#ffffff`, 주요 텍스트 `#111827`, 보조 텍스트 `#374151`, 설명 `#6b7280`, 경계 `#e5e7eb`, 브랜드 빨강 `#ef4444`를 유지한다. 본문·제목에는 기존 Inter/system sans, 코드·저장소 이름에는 monospace를 사용한다. 히어로와 섹션 제목은 가운데 정렬, 카드·문서 내용은 왼쪽 정렬을 유지한다.

- 새 스타일은 가능한 한 `--color-surface`, `--color-text-muted`, `--color-brand` 같은 의미 토큰을 사용한다.
- 투명도가 필요하면 `rgb(var(--palette-red-500) / 0.13)`처럼 공통 팔레트를 참조한다.
- 간격은 `--space-*`, 글자 크기는 `--font-size-*`, 모서리는 `--radius-*`, 그림자는 `--shadow-*`를 사용한다.
- 기술 스택의 공식 브랜드 색과 기능 데모의 콘텐츠별 색은 `data/`에 유지한다. 멤버·포지션의 동적 색상은 `accentStyle()`을 통해 `--ui-accent-rgb`로 전달한다.
- CSS와 JavaScript가 함께 사용하는 전환 시간은 `--duration-*` 하나로 관리한다. JavaScript는 `readMotionDuration()`으로 읽는다.
- 미디어 쿼리의 640px·768px·1024px 경계값은 CSS 사용자 정의 속성을 직접 사용할 수 없으므로 리터럴로 유지한다.
- 기존 유틸리티 클래스는 호환용으로 유지하되 같은 토큰을 참조한다. 새 반복 UI는 긴 유틸리티 목록을 복사하기보다 공통 컴포넌트를 사용한다.

## 사용 예

```html
<a class="ui-button ui-button--primary" href="/todo">개발 진행 현황 보기</a>
<section class="ui-section">
  <div class="ui-container ui-container--content">
    <header class="ui-section-heading">
      <h2>섹션 제목</h2>
      <p>섹션 설명</p>
    </header>
  </div>
</section>
```

```js
renderBadge(technology.name, { variant: "technology", color: technology.color });
renderMemberCard(member, { profile: true });
```

저장소 카드는 `data/repositories.json`에 콘텐츠만 추가한다. 링크·언어 통계 이미지·라벨·호버 표현은 `renderRepositoryCard()`가 공통으로 생성한다. 멤버 카드는 `profile: true`일 때 팀원 목록용, 생략하면 포지션 목록용이다. 포지션용 배지·카드에는 부모 요소의 `accentStyle(position.color)`가 필요하다.

로고는 `brand-mark.html`을 `@include`로 사용하며 부모의 `--brand-width`, `--brand-height`로 크기를 지정한다. 장식 아이콘과 로고는 `aria-hidden`으로 숨기고 링크·버튼 자체에 읽을 수 있는 이름을 제공한다.

## 변경 확인

`npm run build`와 기존 핵심 규칙 단위 테스트인 `npm test`를 실행하고 홈·TODO 목록·문서 모달을 브라우저에서 확인한다. 좁은 화면, 키보드 포커스, 모션 감소 설정을 함께 고려한다. 디자인 토큰·마크업·UI 동작을 위한 회귀·통합 테스트는 추가하지 않는다.
