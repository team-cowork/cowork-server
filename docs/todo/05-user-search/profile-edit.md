# 프로필 필드 직접 수정 API

- **서비스**: cowork-user
- **우선순위**: 🟡

## 문제

현재 프로필은 조회만 가능하고 수정 API가 없다.

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `PATCH` | `/users/me` | 닉네임·이름·설명·GitHub 수정 |
| `PATCH` | `/users/me/status` | 상태 + 커스텀 상태 메시지 설정 |

## 커스텀 상태 메시지 요청 예시

```json
{
  "status": "DO_NOT_DISTURB",
  "message": "집중 중",
  "expiresAt": "2026-05-26T18:00:00"
}
```
