# 웹훅

외부 서비스(GitHub Actions, 배포 알림 등)가 채널에 메시지를 보낼 때 웹훅을 씁니다.

## 웹훅 만들기

1. 채널 관리 권한이 있는 사용자가 텍스트 채널이나 웹훅 채널에서 웹훅을 만듭니다.
2. 응답의 `executeUrl`을 외부 서비스에 등록합니다.

```bash
curl -X POST "$EXECUTE_URL" \
  -H "Content-Type: application/json" \
  -d '{"content": "배포가 완료되었습니다.", "username": "GitHub Actions"}'
```

## 주의 사항

- `executeUrl`에는 인증 토큰이 들어 있습니다. 외부에 노출되면 수정 API에서 `rotateToken: true`로 토큰을 바꿉니다.
- `username`을 보내지 않으면 웹훅 이름으로 표시합니다.
- 메시지는 4000자까지 보낼 수 있습니다.
- 채널을 삭제하면 채널의 웹훅도 함께 삭제됩니다.
