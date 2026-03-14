# MSA 전환 계획

## 배경

cowork-server는 하나의 Spring Boot 애플리케이션으로 인증, 팀, 프로젝트, 채널, 채팅, 알림, 음성 기능을 제공해 왔다.
기능이 늘어나면서 다음 문제가 반복되었다.

- 채팅과 알림 트래픽이 몰리면 팀·프로젝트 API 응답도 함께 느려진다.
- 한 도메인의 배포가 전체 서비스 재시작으로 이어진다.
- 실시간 기능(채팅, 음성, 알림)과 CRUD 기능의 확장 요구가 서로 다르다.
- 팀원마다 익히고 싶은 기술 스택이 다르지만 하나의 코드베이스에 묶여 있다.

## 목표

- 도메인별로 독립 배포와 독립 확장이 가능한 구조로 전환한다.
- 하나의 저장소(모노레포)에서 서비스를 관리하고, API 게이트웨이로 진입점을 통일한다.
- 서비스 간 비동기 연동은 메시지 브로커(Kafka)로 처리한다.

## 진행 순서

1. 모놀리식 내부에서 도메인 간 직접 참조를 줄이고 이벤트 기반 연동을 정리한다.
2. 게이트웨이가 검증한 사용자 정보를 헤더로 받을 수 있게 한다.
3. 도메인별 테이블 소유 관계를 확정한다.
4. 서비스별 기술 스택을 정하고, 새 모노레포 구조에서 서비스를 다시 구성한다.
5. 모놀리식 운영 배포를 중지하고 저장소를 모노레포 구조로 전환한다.

## 도메인별 테이블 소유 관계

| 도메인 | 테이블 | 비고 |
|---|---|---|
| 인증 | `tb_refresh_tokens` | 사용자 ID만 참조 |
| 사용자 | `tb_users` | DataGSM 계정 정보 동기화 |
| 팀 | `tb_teams`, `tb_team_members`, `tb_team_roles`, `tb_team_role_assignments` | |
| 프로젝트 | `tb_projects`, `tb_project_members` | 팀 ID는 FK 없이 참조 예정 |
| 채널 | `tb_channels`, `tb_channel_members`, `tb_webhooks`, `tb_threads`, `tb_shared_accounts`, `tb_credential_copy_histories`, `tb_meeting_note_*`, `tb_meeting_notes` | |
| 채팅 | `tb_messages`, `tb_message_attachments` | 문서형 저장소로 이전 검토 |
| 설정 | `tb_resource_settings`, `tb_channel_notification_settings` | |
| 알림 | `tb_device_tokens` | |
| 음성 | `tb_voice_sessions`, `tb_voice_participants` | |

서비스를 나누면 도메인 사이의 외래 키는 제거하고, 다른 서비스의 ID를 참조하는 컬럼에는 출처를 주석으로 남긴다.
도메인 이벤트(`TeamDeletedEvent`, `ChannelDeletedEvent` 등)는 Kafka 토픽으로 옮긴다.
