# 알림 설정 (preference 모듈 확장)

- **서비스**: cowork-preference
- **우선순위**: 🟡

## 문제

현재 preference는 테마·언어·시간형식·마케팅이메일만 저장한다. 알림 관련 설정이 없다.

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `PATCH` | `/preferences/notifications` | 알림 설정 변경 |
| `PATCH` | `/preferences/channels/{channelId}/notifications` | 채널별 알림 오버라이드 |

## 추가 필드

```json
{
  "notificationLevel": "ALL | MENTIONS | MUTE",
  "channelOverrides": [{ "channelId": 1, "level": "MUTE" }],
  "desktopNotifications": true,
  "soundEnabled": true
}
```
