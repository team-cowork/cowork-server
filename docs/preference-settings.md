# cowork-preference 설정 키 명세

`resource_setting` 테이블의 `settings` JSONB 컬럼에 저장되는 유효 키 목록입니다.
`SettingSchema.filter()`가 허용 목록 기준으로 필터링하므로 아래 키 외의 필드는 저장되지 않습니다.

---

## ACCOUNT

```json
{
  "status": "ONLINE",
  "status_expires_at": "2026-04-20T12:00:00Z",
  "marketing_email": true,
  "theme": "WHITE",
  "language": "KO",
  "time_format": "24H"
}
```

| 키 | 타입 | 허용 값 | 설명 |
|----|------|---------|------|
| `status` | string | `ONLINE`, `DO_NOT_DISTURB` | 현재 상태. 사용자가 수동으로 `OFFLINE` 설정 불가 — 400 반환 |
| `status_expires_at` | string (ISO-8601) | UTC datetime (예: `2026-04-20T12:00:00Z`) | 상태 자동 만료 시각. 생략 시 무기한 유지. 만료되면 `preference.status.changed` Kafka 이벤트 발행 (reason=`EXPIRED`) |
| `marketing_email` | boolean | `true`, `false` | 마케팅 이메일 수신 동의 여부 |
| `theme` | string | `WHITE`, `BLACK` | UI 테마 |
| `language` | string | `KO`, `EN` | 표시 언어 |
| `time_format` | string | `12H`, `24H` | 시간 표기 형식 |

**주의사항**
- `status`와 `status_expires_at`은 독립적으로 업데이트 가능
- `status_expires_at`만 업데이트할 경우 기존 `status`의 만료 큐가 새 만료 시각으로 갱신됨
- 상태 변경 시 `preference.status.changed` Kafka 이벤트 발행 (reason=`MANUAL`)

---

## TEAM

```json
{
  "tag_spam_block": true
}
```

| 키 | 타입 | 허용 값 | 설명 |
|----|------|---------|------|
| `tag_spam_block` | boolean | `true`, `false` | 팀 내 태그 스팸 차단 활성화 여부 |

---

## PROJECT

현재 저장되는 일반 설정 키 없음. 추후 확장 예정.
프로젝트 Role 정의/할당은 `project_role_definition`, `account_project_role` 테이블로 별도 관리됩니다.

---

## VOICE_CHANNEL

```json
{
  "bitrate": 64000,
  "max_participants": 50
}
```

| 키 | 타입 | 허용 값 | 설명 |
|----|------|---------|------|
| `bitrate` | number | bps 단위 정수 (예: `64000`) | 오디오 비트레이트 |
| `max_participants` | number | 양의 정수 | 동시 참가 가능 최대 인원 |

---

## TEXT_CHANNEL

```json
{
  "webhook": {
    "is_active": true,
    "secret_key": "hmac-secret-string",
    "retry_count": 3,
    "retry_interval_ms": 1000
  }
}
```

| 키 | 타입 | 설명 |
|----|------|------|
| `webhook.is_active` | boolean | Webhook 활성화 여부 |
| `webhook.secret_key` | string | HMAC 서명에 사용할 시크릿 키 |
| `webhook.retry_count` | number | 전송 실패 시 재시도 횟수 |
| `webhook.retry_interval_ms` | number | 재시도 간격 (밀리초) |

**주의사항**
- `webhook` 객체는 **PUT 전체 교체 계약** — 부분 업데이트(PATCH) 미지원
- 클라이언트는 항상 `webhook` 객체 전체를 전송해야 함. 누락된 내부 필드는 삭제됨
- `webhook` 내부의 정의되지 않은 키는 `SettingSchema.filter()`에서 제거됨

---

## 채널별 알림 (account_channel_notification)

```json
{
  "notification": false
}
```

| 키 | 타입 | 설명 |
|----|------|------|
| `notification` | boolean | 해당 채널의 알림 수신 여부 |

**주의사항**
- `account_channel_notification` 테이블에 row가 없으면 알림 ON(`true`)이 기본값
- row가 존재할 때만 OFF 설정이 유효함
- `PUT /preferences/account/{accountId}/channels/{channelId}/notification`으로 관리

---

## Kafka 이벤트

### `preference.status.changed`

상태가 변경되거나 만료될 때 발행됩니다.

```json
{
  "accountId": 123,
  "previousStatus": "DO_NOT_DISTURB",
  "newStatus": "ONLINE",
  "reason": "MANUAL",
  "timestamp": "2026-04-20T12:00:00Z"
}
```

| 필드 | 설명 |
|------|------|
| `reason` | `MANUAL` (사용자 직접 변경) 또는 `EXPIRED` (status_expires_at 만료) |
| `newStatus` | 만료 시 `null` |