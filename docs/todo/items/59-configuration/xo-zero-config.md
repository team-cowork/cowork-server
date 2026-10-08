# JS·TS 모듈 XO 기본 설정 전면 적용

- **서비스**: cowork-chat, cowork-promotion, Node CI
- **우선순위**: 🟡 낮음
- **현재 상태**: 병합된 XO 도입 코드에는 모듈별 커스텀 설정과 인라인 규칙 예외가 남아 있으며, 기본 설정만으로의 전면 적용은 수행하지 않았다.
- **관련 작업**: 병합 완료된 [기존 린트 정리 #437](https://github.com/team-cowork/cowork-server/pull/437), [CI 린트 필수화 #438](https://github.com/team-cowork/cowork-server/pull/438), [XO 도입 #439](https://github.com/team-cowork/cowork-server/pull/439)

## 문제

`cowork-chat/xo.config.mjs`와 `cowork-promotion/xo.config.mjs`는 `space: 4`, 규칙 옵션 변경·비활성화, 파일별 예외, 전역 변수 및 검사 제외 설정을 선언한다. 소스에도 `eslint-disable-next-line`이 남아 있다. 두 모듈의 `package.json`은 파일 glob을 지정해 XO 검사 범위를 JS·TS와 일부 설정 파일로 제한한다.

이 구성은 기존 코드와 실행 환경을 유지하면서 XO를 도입한 상태다. 후속 작업에서는 프로젝트가 XO 기본값에 맞도록 코드·빌드 구조를 정리하고, 커스텀 옵션 없이 실행 가능한 상태로 전환한다. 기본 설정으로 전체 대상을 검사했을 때의 진단 목록과 수정 규모는 아직 검증하지 않았다.

## 적용 원칙

[XO 공식 문서](https://github.com/xojs/xo#readme)의 `Zero-config` 방향을 따른다. XO는 커스터마이징도 지원하지만, 이 작업에서는 사용자 요구에 따라 기본 설정만 사용하는 것을 목표로 한다.

| 대상 | 전환 기준 |
|---|---|
| 규칙·스타일 | `rules` 덮어쓰기와 `space` 등 사용자 옵션을 제거하고 기본값에 맞게 수정한다. |
| 파일별 예외 | 인라인 비활성화, suppression 파일, 경로별 규칙 완화로 오류를 숨기지 않는다. |
| 검사 범위 | CLI glob·사용자 ignore를 통한 범위 축소를 제거하고 선택한 XO 버전의 기본 파일 탐색·제외 동작을 따른다. |
| 환경·모듈 형식 | 커스텀 globals·parser 설정·플러그인으로 예외를 옮기지 않고, 필요한 소스·TypeScript·빌드 구조 변경을 조사하고 반영한다. |
| 실행 경로 | 로컬·에디터·CI가 동일한 잠금 파일의 XO 기본 규칙을 사용한다. CI의 `--max-warnings=0`은 실패 기준으로 유지한다. |

## 할 일

### 기본 설정 전환

- 작업 시점의 XO 버전과 공식 기본 동작을 확인하고, 두 모듈의 무설정 실행 진단을 수집한다.
- `xo.config.mjs`, `eslint.config.mjs`, `package.json`, 인라인 지시문, ignore·suppression 경로를 함께 점검하고 커스텀 규칙·옵션을 제거한다.
- `lint`는 파일 인자 없는 `xo`, `lint:fix`는 같은 실행 경로의 `--fix`로 정리한다. 에디터용 어댑터가 필요하면 기본 설정을 그대로 전달하는 연결만 유지한다.
- 포맷과 자동 수정 가능한 항목을 적용한 뒤, 나머지는 코드 구조를 수정해 해결한다. 기존 규칙 예외를 다른 설정 파일로 옮기지 않는다.

### 실행 계약과 문서 정리

- NestJS 데코레이터 메타데이터, CommonJS·ESM 로딩, Promise 전달·거부 처리, Mongoose 쿼리 실행 시점, CLI 종료 전 정리 작업을 확인하며 수정한다.
- promotion의 브라우저 API 지원 범위, DOM 선택·렌더링 동작, CSS 단위 파싱, 번들 생성 계약을 유지하도록 기본 규칙에 맞게 수정한다.
- 기본값으로 해결되지 않는 프레임워크·런타임 제약은 필요한 구조 변경과 함께 기록하고, 예외를 남긴 상태를 완료로 처리하지 않는다.
- `CONTRIBUTING.md`, 두 모듈의 `README.md`, `.github/workflows/cowork-stage-ci.yml`, `.github/workflows/cowork-prod-ci.yml`의 실행 안내와 검사를 새 기준에 맞춘다.

## 검증

- 두 모듈의 지원 Node 버전에서 `npm ci`, `npm run lint -- --max-warnings=0`, `npm run build`를 수행한다.
- 대표 파일의 XO 유효 설정과 무설정 기본값을 대조하고, 설정·스크립트·소스에 규칙 완화나 검사 우회가 남아 있는지 정적으로 확인한다.
- 변경한 핵심 비즈니스 판단에 한해서만 단위 테스트를 작성·실행한다. 회귀·통합 테스트는 작성·실행하지 않는다.
- 데코레이터 메타데이터와 모듈 로딩은 빌드 산출물·설정으로 확인하고, 브라우저 화면과 사용자 동작은 수동 확인한다.

## 완료 조건

- 두 모듈이 커스텀 XO 규칙·옵션과 인라인 예외 없이 기본 검사 범위의 린트를 통과한다.
- 로컬·에디터·CI가 동일한 기본 규칙을 적용하며 CI에서 오류와 경고를 거부한다.
- 빌드와 핵심 비즈니스 단위 테스트가 통과하고 실행 계약 검토 결과가 기록되어 있다.
- 프로젝트 문서에 커스텀 스타일·예외를 전제로 한 안내가 남아 있지 않다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#437](https://github.com/team-cowork/cowork-server/pull/437) · [#438](https://github.com/team-cowork/cowork-server/pull/438) · [#439](https://github.com/team-cowork/cowork-server/pull/439).
- [대조 코드](../../../../cowork-chat/xo.config.mjs): XO와 CI lint는 병합되어 있다. 양 모듈의 space·rules·ignore·인라인 예외와 CLI glob이 남아 있어 zero-config 전환은 아니다.
- 판정: **부분 구현**. 기본 규칙·기본 탐색 범위로 코드·빌드 구조 정리를 남은 범위로 유지한다.
