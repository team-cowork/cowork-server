# 채널·프로젝트 권한 전환

## 구현과 데이터

- 팀 채널·프로젝트의 멤버 추가·삭제 API, 프로젝트 멤버 역할 변경 API, 멤버 저장·동기화 코드를 제거한다. 권한 변경으로 멤버 행을 만들거나 지우지 않는다.
- 채널 목록·상세·접근자 조회는 텍스트 `message_read`, 음성 `voice_connect`를 검사한다. 음성 서비스도 `voice_connect`를 검사한다. 채팅 본문 읽기는 `message_read`, 작성은 읽기 권한과 `message_send`가 필요하다.
- 비공개 채널은 해당 채널의 `message_read` 또는 `voice_connect` 명시 허용이 필요하다. 팀 역할 기본값은 비공개 채널의 읽기 권한을 주지 않는다. 목록에서는 접근할 수 없는 항목만 숨긴다.
- 프로젝트는 역할의 `read`·`write` 권한을 사용한다. Preference가 계산한 권한 상태를 Project와 Chat이 구독한다. 행의 존재는 권한이 아니다. 수정에는 두 권한이 모두 필요하다.
- 높은 역할 `priority`가 우선한다. 같은 순위에서는 거부가 우선한다. 누락된 권한은 false다. 채널의 팀 OWNER, 프로젝트의 생성자·팀 OWNER/ADMIN은 기존 관리 권한을 유지한다. 모두 현재 팀 멤버여야 한다.
- 재정렬은 채널 `channel_reorder`, 프로젝트 `reorder`로 별도 판단한다. 생성자 또는 현재 팀 OWNER/ADMIN도 재정렬할 수 있다. 메시지 작성 `message_send`나 프로젝트 수정 `write`만으로는 재정렬할 수 없다.
- 팀 초대와 DM 참여는 유지한다. DM 참여와 채팅 읽음 위치는 팀 채널 권한과 별도로 저장한다.
- 기존 팀 채널·프로젝트 멤버 기록은 권한으로 자동 변환하지 않는다. 접근을 유지할 사용자에게 필요한 역할을 부여해야 한다.
- Project의 미사용 테이블 `tb_project_members`, `tb_project_member_event_tombstones`는 `V27__project_role_access_projections.sql`에서 삭제한다.
- Channel의 테이블 이름은 `tb_dm_participants`, `tb_dm_participant_event_states`로, Preference의 테이블 이름은 `tb_dm_participant_projections`로 변경한다. 데이터는 보존하고 인덱스·제약조건·주석도 DM 참여 용어로 맞춘다. `channel_id`, `user_id`·`account_id`, `joined_at`은 DM에서도 의미가 유효하므로 유지한다.
- Chat의 `channelmembers` 컬렉션은 기존 읽음 위치와 DM 숨김 값을 읽는 데 사용하므로 유지한다.
- `channel_member_manage`는 기존 역할 이벤트 재생을 위한 키로만 남는다. 이 키에 대응하는 멤버 관리 기능은 없다.

## 클라이언트 호출 변경

모든 경로는 Gateway 기준이다.

| 상황 | 제거한 호출 | 변경할 호출 |
| --- | --- | --- |
| 채널 접근 허용 | `POST /api/channel/channels/{channelId}/members` | `PUT /api/team/teams/{teamId}/members/{userId}/roles/{roleId}` |
| 채널 접근 역할 회수 | `DELETE /api/channel/channels/{channelId}/members/{memberId}` | `DELETE /api/team/teams/{teamId}/members/{userId}/roles/{roleId}` |
| 프로젝트 접근 허용 | `POST /api/project/projects/{projectId}/members` | `POST /api/preference/preferences/project/{projectId}/roles/{roleName}/members` — 본문 `{"accountId":217}` |
| 프로젝트 접근 역할 회수 | `DELETE /api/project/projects/{projectId}/members/{memberId}` | `DELETE /api/preference/preferences/project/{projectId}/roles/{roleName}/members/{accountId}` |
| 프로젝트 역할 변경 | `PATCH /api/project/projects/{projectId}/members/{memberId}` | 기존 역할 회수 후 새 역할 부여 |

채널 역할 정책은 `PUT /api/channel/channels/{channelId}/role-policies/{roleId}`로 설정한다. 텍스트 보기 본문은 `{"permissions":{"message_read":true}}`, 음성 보기는 `{"permissions":{"voice_connect":true}}`다. 이 정책은 해당 역할을 가진 모든 사용자에게 적용된다. 팀 역할 회수는 그 역할이 주던 다른 채널 권한에도 영향을 준다. 한 사람의 한 채널 접근을 관리하려면 그 범위를 표현하는 역할을 사용한다. 공개 채널의 정책 삭제는 역할 기본값으로 복귀하므로 접근 회수와 같지 않다. 비공개 채널은 명시 허용이 없으면 숨긴다.

팀 역할 부여·회수와 채널 정책 변경에는 `Idempotency-Key`가 필요하다. `202` 수락 후 다음 경로에서 `SUCCEEDED`를 확인한다.

- `GET /api/team/teams/{teamId}/role-operations/{operationId}`
- `GET /api/channel/channels/{channelId}/role-policies/{roleId}/operations/{operationId}`

프로젝트 역할이 없으면 먼저 `POST /api/preference/preferences/project/{projectId}/roles`를 호출한다. 예: `{"roleName":"viewer","priority":10,"permissions":{"read":true,"write":false}}`.

`GET /api/channel/channels/{channelId}/members`와 `GET /api/project/projects/{projectId}/members`는 접근 가능한 사용자 조회로 유지한다. 응답 항목은 각각 `{channelId,userId}`, `{projectId,userId,write}`다. 과거 멤버 행의 `id`, 가입 시각, `OWNER/EDITOR/VIEWER` 필드를 사용하던 클라이언트는 변경해야 한다.

## 채팅 작업 권한

팀 채널의 모든 채팅 작업에는 `message_read`가 필요하다. 아래 권한은 기존 채널 역할 정책 API의 `permissions`에 각각 설정한다. `message_send`로 다른 작업 권한을 대신할 수 없다.

| 상황 | 호출 | 추가 권한 |
| --- | --- | --- |
| 일반 메시지 전송 | `POST /api/chat/channels/{channelId}/messages` | `message_send` |
| 답글 전송 | 위 경로에 `parentMessageId` 포함 | `message_send`, `thread_reply` |
| 첨부 메시지 전송 | 위 경로에 `attachments` 포함 | `message_send`, `file_attach` |
| 업로드 URL 발급 | `POST /api/chat/channels/{channelId}/files/presigned-url` | `file_attach` |
| 업로드 완료 확인 | `POST /api/chat/channels/{channelId}/files/confirm` | `file_attach` |
| 반응 추가 | `POST /api/chat/channels/{channelId}/messages/{messageId}/reactions` | `reaction_add` |

첨부된 답글은 세 권한이 모두 필요하다. 본인의 반응 제거는 읽기 권한으로 가능하다. DM은 대화 참여 여부로 판단한다.

## 재정렬 호출

- 채널: `PATCH /api/channel/teams/{teamId}/channels/reorder`, 본문 `{"orderedChannelIds":[11,9]}`.
- 프로젝트: `PATCH /api/project/teams/{teamId}/projects/reorder`, 본문 `{"orderedProjectIds":[4,2]}`.
- 두 요청 모두 조회 가능한 ID 전체를 원하는 순서로 보낸다. 숨겨진 항목의 ID는 보내지 않는다. 위치가 실제로 바뀌는 모든 항목에 관리 또는 재정렬 권한이 필요하다. 권한이 없는 항목을 이동하면 전체 요청을 `403`으로 거부한다. 숨겨진 항목과 위치가 그대로인 항목은 이동하지 않는다.
- 채널 재정렬 권한은 위 채널 정책 API에 `{"permissions":{"channel_reorder":true}}`로 부여하거나 팀 역할의 기본 권한에 설정한다. 프로젝트 역할에는 `{"read":true,"write":false,"reorder":true}`를 설정한다. 각 역할의 부여·회수 경로는 위 표와 같다.
- `message_send`는 메시지 전송 권한이다. 채널 정보 수정 권한을 주지 않는다. 프로젝트의 `write`는 프로젝트 수정 권한이며 메시지 전송 권한과 별개다.

## CD 적용 범위

정상 Prod CD는 인프라 적용 → Config 배포 → 앱 배포 순서다. 이번 변경에 필요한 SQL은 앱 시작 시 Flyway가 적용한다. `deploy/compose/stack.yaml`에서 추가한 `channel.dm-participant.state.v1`, `preference.project-access.state.v1` 토픽과 compact 정책은 IaC가 앱 배포 전에 적용한다. 새 소비자는 원본 snapshot을 읽고 readiness가 열린 뒤 요청을 처리한다. 정상 전환에 수동 SQL 실행이나 VM에서 Kafka 토픽을 만드는 작업은 필요하지 않다.

Project의 V27 마이그레이션은 기존 프로젝트 멤버 기록과 삭제 이력을 영구 삭제한다. 이전 버전으로 되돌리려면 삭제 전 DB 백업을 복원해야 한다.

CD는 여러 앱을 병렬로 전환하며 전체 트래픽의 일괄 차단·재개는 하지 않는다. 이전 버전과 새 클라이언트의 혼용을 막는 전환 절차는 별도로 필요하다: 대상 트래픽 차단 → CD 완료·readiness 확인 → 새 클라이언트 적용 → 트래픽 재개. 트래픽 제어 위치는 운영 ingress 설정에 따라 결정한다.

Voice는 `KAFKA_GROUP_ID_CHANNEL_ACCESS`, Preference는 `KAFKA_GROUP_ID_DM_PARTICIPANT_PROJECTION`·`KAFKA_TOPIC_DM_PARTICIPANT_STATE`를 사용한다. 저장소 기본값은 새 스트림에 맞춰져 있다. 외부 설정에서 새 키를 재정의한다면 동일한 스트림을 지정해야 한다.

기존 `channel.member.event.v2`, `project.member.event.v2` 토픽과 미처리 outbox는 이번 배포에서 삭제하지 않는다. 이전 producer 중단, 새 consumer 준비, 삭제 이력 보존을 확인한 뒤 폐기한다. 기존 멤버 기록을 권한으로 자동 복구하는 작업은 CD에 포함되지 않는다.
