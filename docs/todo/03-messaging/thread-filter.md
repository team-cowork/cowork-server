# 스레드 메시지 `parentMessageId` 필터

- **서비스**: cowork-chat
- **우선순위**: 🟡

## 문제

`GET /channels/{channelId}/messages`에 `parentMessageId` 쿼리 파라미터가 없다.
현재 클라이언트는 이미 로드된 메시지에서 클라이언트 필터링으로 임시 처리 중이다.

## 해결

```
GET /channels/{channelId}/messages?parentMessageId={id}
```

파라미터가 있으면 해당 메시지의 스레드 답글만 반환, 없으면 기존 동작 유지.
