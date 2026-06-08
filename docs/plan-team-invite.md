# 팀 초대 링크 API 구현 계획

## 결정 사항

| 항목 | 결정 |
|------|------|
| 초대 코드 형식 | 8자리 alphanumeric (SecureRandom + Base62) |
| 만료 정책 | 서버 옵션: `1d` / `7d` / `30d` / `never` |
| 팀당 활성 링크 수 | 무제한 |
| 생성 권한 | 모든 팀 멤버 |
| 가입 기본 역할 | `MEMBER` 고정 |
| 중복 가입 | `409 Conflict` |
| 가입 이벤트 | `MEMBER_JOINED` → `team.lifecycle` |
| 삭제 권한 | 생성자 본인 또는 OWNER/ADMIN |
| 목록 조회 범위 | 전체 (만료 포함) |
| 목록 조회 권한 | 모든 팀 멤버 |

---

## API

| 메서드 | 경로 | 권한 |
|--------|------|------|
| `POST` | `/teams/{teamId}/invites` | 팀 멤버 |
| `GET` | `/teams/{teamId}/invites` | 팀 멤버 |
| `DELETE` | `/teams/{teamId}/invites/{inviteCode}` | 생성자 본인 또는 OWNER/ADMIN |
| `POST` | `/teams/join/{inviteCode}` | 인증된 사용자 (비멤버) |

---

## 요청 / 응답

### POST /teams/{teamId}/invites → 201

```json
// Request
{ "duration": "7d" }

// Response
{
  "inviteCode": "aB3xK9mZ",
  "teamId": 1,
  "createdBy": 42,
  "duration": "7d",
  "expiresAt": "2026-06-13T00:00:00",  // never면 null
  "createdAt": "2026-06-06T00:00:00"
}
```

### GET /teams/{teamId}/invites → 200

```json
[
  {
    "inviteCode": "aB3xK9mZ",
    "teamId": 1,
    "createdBy": 42,
    "duration": "7d",
    "expiresAt": "2026-06-13T00:00:00",
    "expired": false,
    "createdAt": "2026-06-06T00:00:00"
  }
]
```

### DELETE /teams/{teamId}/invites/{inviteCode} → 204

- 없는 코드 / 다른 팀 코드 → 404
- 권한 없음 → 403

### POST /teams/join/{inviteCode} → 200

```json
{ "teamId": 1, "userId": 99, "role": "MEMBER", "joinedAt": "..." }
```

- 없거나 삭제된 코드 → 404
- 만료된 코드 → 410 Gone
- 이미 멤버 → 409 Conflict

---

## DB: V4__add_team_invites.sql

```sql
CREATE TABLE tb_team_invites
(
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    team_id     BIGINT      NOT NULL,
    invite_code VARCHAR(8)  NOT NULL UNIQUE,
    created_by  BIGINT      NOT NULL,
    duration    VARCHAR(10) NOT NULL,        -- '1d' | '7d' | '30d' | 'never'
    expires_at  DATETIME(6) NULL,            -- NULL = 영구
    deleted_at  DATETIME(6) NULL,            -- NULL = 활성, NOT NULL = 수동 삭제
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_tb_team_invites_team_id (team_id),
    INDEX idx_tb_team_invites_invite_code (invite_code)
);
```

- 활성 링크 조건: `deleted_at IS NULL AND (expires_at IS NULL OR expires_at > NOW())`
- soft delete → 목록 히스토리 유지 가능

---

## 구현 파일 목록

```
cowork-team/src/main/kotlin/com/cowork/team/
├── domain/TeamInvite.kt
├── repository/TeamInviteRepository.kt
├── service/TeamInviteService.kt
├── controller/TeamInviteController.kt
└── dto/
    ├── CreateInviteRequest.kt
    ├── InviteResponse.kt
    └── JoinTeamResponse.kt

cowork-team/src/main/resources/db/migration/
└── V4__add_team_invites.sql
```

---

## 구현 순서

1. `V4__add_team_invites.sql`
2. `TeamInvite.kt` (Entity)
3. `TeamInviteRepository.kt`
4. DTO 3개
5. `TeamInviteService.kt`
6. `TeamInviteController.kt`
7. `TeamEventPublisher.publishMemberJoined()` 추가
8. `TeamInviteServiceTest.kt`
