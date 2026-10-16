# Preference 신규 설정 값 적용

- **서비스**: cowork-chat, cowork-notification, cowork-preference
- **우선순위**: 🟡 낮음
- **현재 상태**: 슬로우 모드·알림 수준·채널 음소거·방해 금지 예약 설정은 Preference에 저장되지만 메시지 전송과 알림 발송에 반영되지 않는다.
- **관련 작업**: [역할 권한 키 강제 적용](../71-security/role-permission-enforcement.md)

## 문제

`cowork-preference`의 `SettingSchema`는 TEXT_CHANNEL의 `slowmode_seconds`(0~21600초),
ACCOUNT의 `notification_level`·`dnd_schedule`·`timezone`, TEAM의 `default_notification_level`을 검증해 저장한다.
계정별 채널 알림(`account_channel_notification.settings`)은 `notification_level`(`ALL`·`MENTIONS`·`NONE`)과
`muted_until`을 저장하고, `preference.channel-notification.changed`에 `notificationLevel`·`mutedUntil`을 함께 싣는다.
하위 호환 값인 `notification`은 `notification_level`이 `NONE`이 아니면 `true`다.

이 값을 읽는 서비스는 없다. `cowork-chat`에는 사용자별 전송 간격 제한이 없다. `cowork-notification`의
`ChannelNotificationEvent`는 `notification`만 역직렬화하므로 `MENTIONS` 수준, 음소거 기한, 방해 금지 예약, 팀 기본 알림
수준을 구분하지 않는다. `resource_setting`의 계정·팀 설정은 `preference.status.changed`(계정 `status`)와
`preference.team.setting.changed`(닉네임 형식) 외에는 Kafka로 전파되지 않아 다른 서비스가 projection으로 받을 수 없다.

## 결정할 정책

| 항목 | 선택지 | 비고 |
|------|--------|------|
| 알림 수준 우선순위 | 채널별 설정 → 계정 `notification_level` → 팀 `default_notification_level` / 다른 순서 | 채널별 행이 없을 때의 기본값을 정한다 |
| 방해 금지 예약 판정 위치 | `cowork-notification` 발송 시점 / 클라이언트 표시 시점 | `timezone` 기준 HH:mm이고 자정을 넘길 수 있다 |
| 슬로우 모드 우회 | OWNER·ADMIN 우회 / `message_manage` 보유자 우회 / 우회 없음 | Discord는 채널·메시지 관리 권한 보유자에게 적용하지 않는다 |

## 설정 전파 방식

다른 서비스들은 REST 없이 Kafka로만 통신하고, 다른 서비스 소유 데이터의 조회는 state topic을 받아 둔 로컬 projection에서
한다. Kafka 요청·응답으로 값을 읽어 오는 조회는 코드베이스에 없다. 같은 방식으로 Preference가 이미
`preference.github-repo.setting.state`를 발행하고 `cowork-project`의 `GithubRepoSettingStateConsumer`가 projection으로 받는다.

계정·팀·채널 설정도 이 구조를 따른다. Preference가 리소스 타입별 설정 state topic을 발행하고, `cowork-notification`과
`cowork-chat`이 필요한 키만 projection으로 저장한다. `resource_setting.state_occurred_at`은 이미 단조 증가 버전이므로
state record의 source version으로 쓴다.

## 할 일

### 설정 state topic (`cowork-preference`)

- 계정·팀·텍스트 채널 설정 state topic을 `<domain>[.<resource>...].state.v<major>` 규칙으로 정하고, compact retention으로 `deploy/compose/stack.yaml`에 프로비저닝한다.
- `PreferenceService.updateSettings`에서 설정 변경과 state record를 같은 outbox transaction에 기록한다.
- `PreferenceSnapshotPublisher`에 리소스 타입별 full snapshot과 partition별 완료 marker 발행을 추가한다.
- 리소스 삭제 상태를 snapshot에 남길 방법(tombstone 보관 여부)을 정한다.

### 알림 (`cowork-notification`)

- `ChannelNotificationEvent`에 `notificationLevel`·`mutedUntil`을 추가하고 projection에 저장한다.
- `MENTIONS` 수준에서는 멘션 알림만 보내고, `muted_until` 이전에는 채널 알림을 보내지 않는다.
- 계정 `notification_level`·`dnd_schedule`·`timezone`과 팀 `default_notification_level`을 state topic projection으로 받아 발송 판단에 반영한다.

### 채팅 (`cowork-chat`)

- 텍스트 채널 설정 `slowmode_seconds`를 state topic projection으로 받고, 메시지 전송 시 사용자별 마지막 전송 시각과 비교해 제한한다.

## 검증

- 알림 수준·음소거 기한·방해 금지 예약(자정을 넘기는 구간 포함)의 발송 여부를 단위 테스트로 확인한다.
- 슬로우 모드 간격 이내 재전송이 거부되고 우회 대상은 통과하는지 확인한다.
- 설정 projection이 snapshot 완료 전에는 readiness를 열지 않는지 확인한다.

## 완료 조건

- 채널 알림 수준이 `NONE`이거나 `muted_until` 이전인 계정에는 해당 채널 알림이 발송되지 않는다.
- 방해 금지 예약 시간에는 계정의 `timezone` 기준으로 알림이 억제되어 있다.
- 슬로우 모드가 설정된 채널에서 간격 이내의 연속 전송은 거부된다.
- 계정·팀·텍스트 채널 설정은 state topic으로 전파되고, 소비 서비스는 로컬 projection에서만 읽는다.
