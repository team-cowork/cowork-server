# 설정 항목

`PUT /preferences/{resourceType}/{resourceId}`로 저장할 수 있는 항목이다. 목록에 없는 키는 무시하고, 값을 `null`로 보내면 해당 항목을 지운다.

## 계정 (`account`)

| 키 | 값 | 비고 |
|---|---|---|
| `status` | `ONLINE`, `DO_NOT_DISTURB` | 다른 사용자에게도 보인다 |
| `status_expires_at` | ISO-8601 시각 | 지나면 `ONLINE`으로 돌아간다 |
| `theme` | `WHITE`, `BLACK` | |
| `language` | `KO`, `EN` | |
| `time_format` | `12H`, `24H` | |
| `date_format` | `YYYY_MM_DD`, `MM_DD_YYYY`, `DD_MM_YYYY` | |
| `marketing_email` | `true`, `false` | |

## 팀 (`team`)

| 키 | 값 |
|---|---|
| `tag_spam_block` | `true`, `false` |
| `nickname_format_enforced` | `true`, `false` |
| `nickname_format_example` | 문자열 |

## 프로젝트, 채널

| 유형 | 키 | 값 |
|---|---|---|
| `project` | `default_channel_id` | 양의 정수 |
| `text-channel` | `slow_mode_seconds` | 0 ~ 21600 |
| `voice-channel` | `bitrate` | 8000 ~ 384000 |
| `voice-channel` | `max_participants` | 1 ~ 50 |

계정 설정은 본인만 바꿀 수 있고, 팀 설정은 팀 관리 권한, 채널 설정은 채널 관리 권한이 필요하다.
