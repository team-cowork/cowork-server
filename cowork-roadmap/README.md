# cowork-roadmap

전공/포지션별 **온보딩 로드맵** 서비스. Java + Spring Boot WebFlux + R2DBC(MySQL).

## 개념

- **로드맵(Roadmap)**: 트리. `scope`로 구분한다.
  - `GLOBAL`: 전공/포지션별 고정 로드맵 (ADMIN 관리)
  - `TEAM` / `PROJECT`: 팀·프로젝트가 직접 등록·구성하는 커스텀 로드맵
- **노드(Node)**: 트리의 한 칸. **노드 1개 = 문서 1개**(제목/번역 본문/원본 URL/원본 제목) + **관련자료 N개**.
- **관련자료(Reference)**: 노드에 딸린 외부 링크.
- **과제(Assignment)**: 로드맵(또는 특정 노드)을 팀/프로젝트 멤버에게 온보딩 과제로 부여하고 진행 상태를 추적한다.

> 공식 문서의 한글 번역은 사람이 수행하고, 이 서비스는 번역된 내용을 저장·조회한다.

## 스택

- Java 25, Spring Boot 4.1 (WebFlux), Spring Data R2DBC (`io.asyncer:r2dbc-mysql`)
- 마이그레이션: Flyway (JDBC), 런타임 쿼리: R2DBC
- 서비스 디스커버리/설정: Eureka + Config Server
- 권한 검증: cowork-team 호출(`WebClient`, `lb://cowork-team`)
- 포트: `8088`, DB: `cowork_roadmap`

## 권한 모델

- 모든 요청 신원은 Gateway가 주입하는 `X-User-Id`, `X-User-Role` 헤더를 신뢰한다(직접 JWT 검증하지 않음).
- GLOBAL 로드맵 변경: `X-User-Role=ADMIN`
- 커스텀 로드맵은 항상 `owner_team_id`를 가지며, 변경/생성은 해당 팀의 `OWNER`/`ADMIN`(또는 생성자), 조회는 팀 멤버.
- 과제 출제/삭제: 글로벌 ADMIN 또는 해당 팀 `OWNER`/`ADMIN`. 진행 상태 변경: 담당자/출제자/ADMIN.

## 주요 엔드포인트 (Gateway 기준 `/api/roadmaps/**`)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/roadmaps` | 로드맵 생성 |
| GET | `/roadmaps?scope=&category=&teamId=&projectId=` | 로드맵 목록 |
| GET | `/roadmaps/{id}` | 로드맵 메타 |
| GET | `/roadmaps/{id}/tree` | 트리 전체(노드+관련자료 중첩) |
| PATCH/DELETE | `/roadmaps/{id}` | 수정/삭제 |
| POST | `/roadmaps/{id}/nodes` | 노드 생성 |
| PUT | `/roadmaps/{id}/nodes/reorder` | 형제 노드 순서 변경 |
| GET/PATCH/DELETE | `/roadmaps/nodes/{nodeId}` | 노드 상세/수정/삭제(서브트리) |
| GET/POST | `/roadmaps/nodes/{nodeId}/references` | 관련자료 목록/추가 |
| PATCH/DELETE | `/roadmaps/references/{refId}` | 관련자료 수정/삭제 |
| POST | `/roadmaps/assignments` | 과제 출제 |
| GET | `/roadmaps/assignments/me` | 내 과제 목록 |
| GET | `/roadmaps/{id}/assignments` | 로드맵별 과제 |
| PATCH | `/roadmaps/assignments/{id}/status` | 진행 상태 변경 |
| DELETE | `/roadmaps/assignments/{id}` | 과제 삭제 |

## 실행

```bash
./gradlew :cowork-roadmap:bootRun
```

Config → Gateway 기동 후 실행한다(서비스 기동 순서 참고).
