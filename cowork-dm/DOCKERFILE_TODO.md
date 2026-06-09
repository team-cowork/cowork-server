# ✅ cowork-dm 도커 배포 설정 (작업 완료)

> 아래 항목은 모두 완료되었습니다. 기록 보존을 위해 취소선으로 남겨 둡니다.

~~이 모듈에는 아직 컨테이너 배포에 필요한 Dockerfile과 문서가 없습니다.~~
~~운영/로컬 배포 대상에 포함되기 전에 아래 항목을 반드시 완료해야 합니다.~~

## 현재 상태

- `cowork-dm`은 prod CI 매트릭스(`.github/workflows/cowork-prod-ci.yml`)와
  `docker-compose.yml`의 서비스(`cowork-dm:local`, 포트 `8091`)에 이미 등록돼 있음
- ~~그러나 **`prod.dockerfile` / `local.dockerfile`이 존재하지 않아 이미지를 빌드할 수 없음**~~ → 작성 완료
- ~~`docker-compose.override.yml`에도 `cowork-dm`의 `build:` 설정이 빠져 있음~~ → 추가 완료

## 해야 할 일

- [x] ~~**`cowork-dm/local.dockerfile` 작성**~~
  - ~~같은 NestJS(Node 22) 계열인 `cowork-chat/local.dockerfile`을 참고~~
- [x] ~~**`cowork-dm/prod.dockerfile` 작성**~~
  - ~~⚠️ 배포 모델은 **(B) 러너 산출물 → COPY** 방식으로 결정됨~~
  - ~~CI가 네이티브로 빌드한 산출물(`dist/`)을 artifact로 전달받아 런타임 스테이지에서 `COPY`만 하도록 작성 (소스 재빌드 금지)~~
  - ~~동일 모델로 전환될 다른 모듈들과 구조를 통일할 것~~
- [x] ~~**`docker-compose.override.yml`에 `cowork-dm` `build:` 블록 추가**~~
  - ~~context/dockerfile 경로를 다른 Node 모듈(`cowork-chat`)과 동일 패턴으로~~
- [x] ~~**`docker-compose.prod.yml`에 `cowork-dm` 이미지(`${REGISTRY}/cowork-dm:${IMAGE_TAG}`) 추가**~~
- [x] ~~**LOCAL 배포 가이드 갱신: `docs/LOCAL_RUN_GUIDE.md`**~~
  - ~~`## 1. 한눈에 보기` 서비스 표에 `cowork-dm`(포트 `8091`) 추가~~
  - ~~`## 7. 빌드 구조`의 NestJS 서비스 항목에 `cowork-dm` 반영~~
  - `## 6. 서비스 기동 순서`는 DM이 일반 비즈니스 서비스와 동급이라 갱신 불필요

## 참고

- 누락 모듈 전체 점검 결과: `cowork-dm`, `cowork-promotion`(둘 다 prod.dockerfile 없음),
  `cowork-monitoring`(CI/compose 미등록) — 이번 작업 범위는 **`cowork-dm`만**,두 모듈은 작성 필요 없음
