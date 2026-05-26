# 팀 초대 링크 API

- **서비스**: cowork-team 또는 cowork-channel
- **우선순위**: 🟠

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/teams/{teamId}/invites` | 초대 링크 생성 (만료시간 포함) |
| `GET` | `/teams/{teamId}/invites` | 활성 초대 링크 목록 |
| `DELETE` | `/teams/{teamId}/invites/{inviteCode}` | 초대 링크 무효화 |
| `POST` | `/teams/join/{inviteCode}` | 초대 코드로 팀 가입 |
