# 팀 멤버 관리 API

- **서비스**: cowork-team 또는 cowork-channel
- **우선순위**: 🟠

## 문제

현재 `GET /teams/{teamId}/members`가 `userId` 배열만 반환한다. 역할·닉네임 정보 포함이 필요하다.

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/teams/{teamId}/members` | 멤버 목록 (userId, role, joinedAt) — **응답 확장** |
| `DELETE` | `/teams/{teamId}/members/{userId}` | 멤버 추방 (Owner/Admin만) |
| `PATCH` | `/teams/{teamId}/members/{userId}/role` | 역할 변경 (Owner만) |
| `DELETE` | `/teams/{teamId}/members/me` | 팀 탈퇴 (본인) |
