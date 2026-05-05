# cowork-project

프로젝트 관리 서비스. 팀 내에서 업무 단위인 "프로젝트"를 생성/관리하고 멤버에게 역할(OWNER/EDITOR/VIEWER)을 부여한다.

## 스택

- Spring Boot 3.4.4 / Kotlin 2.1.20 / Java 21
- Spring Data JPA + Flyway (MySQL 8.0)
- Spring Cloud Eureka Client, Config Client
- team.themoment.sdk (exception/swagger/logging)

## 런타임

| 항목 | 값 |
| --- | --- |
| 포트 | `8084` |
| 스키마 | `cowork_project` |
| Swagger paths | `/projects/**` |
| Eureka | `http://localhost:8761/eureka/` |

## 도메인 모델

### Project (`tb_projects`)
| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | 자동증가 |
| team_id | BIGINT | `cowork-team`의 팀 ID (FK 아님, MSA) |
| name | VARCHAR(100) | 프로젝트명 |
| description | VARCHAR(500) nullable | 설명 |
| status | VARCHAR(20) | `ACTIVE` / `ARCHIVED` |
| created_by | BIGINT | 생성자 user_id |
| created_at, updated_at | DATETIME(6) | 자동 관리 |

### ProjectMember (`tb_project_members`)
| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | |
| project_id | BIGINT FK→tb_projects ON DELETE CASCADE | |
| user_id | BIGINT | `cowork-user` user_id |
| role | VARCHAR(20) | `OWNER` / `EDITOR` / `VIEWER` |
| joined_at | DATETIME(6) | |

UNIQUE `(project_id, user_id)` — 한 유저는 한 프로젝트 내 한 멤버십만.

## 권한 모델

| 작업 | 권한 |
| --- | --- |
| 프로젝트 생성 | 모든 사용자 (생성자는 자동으로 OWNER) |
| 프로젝트 수정 | OWNER, EDITOR |
| 프로젝트 삭제 | OWNER |
| 멤버 추가/역할 변경/제거 | OWNER |
| 프로젝트 조회 | 인증된 사용자 (공개 조회) |

**불변 규칙**
- `OWNER` 역할은 멤버 추가(`addMember`)나 역할 변경(`updateMemberRole`)으로 부여할 수 없음 → 항상 `BAD_REQUEST`.
- OWNER는 제거할 수 없음 (유일한 OWNER 상실 방지).
- OWNER의 역할은 변경할 수 없음.

## API

모든 인증 엔드포인트는 Gateway가 주입하는 `X-User-Id: <Long>` 헤더를 요구한다.

### Project

| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/projects` | 인증 | 프로젝트 생성 → 201 |
| GET | `/projects/{id}` | - | 프로젝트 상세 (memberCount 포함) |
| PATCH | `/projects/{id}` | OWNER/EDITOR | 이름/설명/상태 수정 |
| DELETE | `/projects/{id}` | OWNER | 프로젝트 삭제 → 204 |
| GET | `/projects?teamId={id}` | - | 팀의 프로젝트 목록 (페이징) |
| GET | `/projects/me` | 인증 | 내가 참여 중인 프로젝트 (페이징, JOIN 단일쿼리) |

### ProjectMember

| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/projects/{id}/members` | OWNER | 멤버 추가 → 201 |
| GET | `/projects/{id}/members` | - | 멤버 목록 |
| PATCH | `/projects/{id}/members/{memberId}` | OWNER | 역할 변경 |
| DELETE | `/projects/{id}/members/{memberId}` | OWNER | 멤버 제거 → 204 |

### 요청/응답 예시

**POST `/projects`**
```json
// Request
{ "teamId": 1, "name": "Q2 OKR", "description": "분기 목표 추적" }

// Response 201
{
  "id": 10, "teamId": 1, "name": "Q2 OKR",
  "description": "분기 목표 추적", "status": "ACTIVE",
  "createdBy": 42,
  "createdAt": "2026-04-14T10:00:00",
  "updatedAt": "2026-04-14T10:00:00"
}
```

**POST `/projects/10/members`**
```json
// Request
{ "userId": 77, "role": "EDITOR" }
```

### 에러 응답

`GlobalExceptionHandler`가 아래와 같이 평탄화된 포맷으로 응답한다.
```json
{ "message": "프로젝트를 찾을 수 없습니다. id=99" }
```

| 케이스 | HTTP |
| --- | --- |
| `ExpectedException` | 해당 status |
| 잘못된 JSON 본문 | 400 |
| 파라미터 타입 불일치 | 400 |
| 필수 헤더 누락 | 400 |
| 기타 | 500 |

## 빌드 & 실행

```bash
# 컴파일
./gradlew :cowork-project:compileKotlin

# 실행 (MYSQL_USER/PASSWORD 환경변수 필요)
./gradlew :cowork-project:bootRun
```
