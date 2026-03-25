# MSA 전환 계획

## 배경

cowork-server는 하나의 Spring Boot 애플리케이션으로 인증, 팀, 프로젝트, 채널, 채팅, 알림, 음성 기능을 제공해 왔다.
처음에는 적은 인원이 모든 도메인을 함께 다뤘기 때문에 하나의 애플리케이션이 개발 속도 면에서 유리했다.
지금은 팀원이 늘어 도메인마다 담당자가 생겼고, 사용자와 트래픽도 학기마다 조금씩 늘고 있다.
그 결과 다음 문제가 반복되었다.

- 여러 담당자가 같은 애플리케이션을 동시에 수정하면서 충돌과 리뷰 대기가 늘었다.
- 한 도메인의 변경도 전체 애플리케이션을 다시 배포해야 해서 배포 일정을 매번 맞춰야 한다.
- 도메인 사이의 경계가 코드에서 강제되지 않아, 다른 도메인의 리포지토리를 직접 참조하는 코드가 계속 생긴다.
- 채팅과 알림 트래픽이 몰리면 팀·프로젝트 API 응답도 함께 느려진다.
- 실시간 기능(채팅, 음성, 알림)과 CRUD 기능은 필요한 확장 방식이 다르지만 함께 늘려야 한다.

팀 규모와 트래픽이 앞으로도 늘어날 것이므로, 도메인을 분리해 담당 팀이 각자 개발·배포·확장할 수 있어야 한다.
팀이 커질 때 도메인별 소유권과 독립 배포를 보장하는 전형적인 방법이 MSA이므로, 서비스 단위로 전환한다.

## 목표

- 도메인마다 담당 팀을 두고, 서비스 단위로 독립 개발과 독립 배포가 가능한 구조로 전환한다.
- 트래픽이 많은 실시간 기능을 다른 도메인과 분리해 따로 확장할 수 있게 한다.
- 하나의 저장소(모노레포)에서 서비스를 관리하고, API 게이트웨이로 진입점을 통일한다.
- 서비스 간 비동기 연동은 메시지 브로커(Kafka)로 처리한다.

## 진행 순서

1. 모놀리식 내부에서 도메인 간 직접 참조를 줄이고 이벤트 기반 연동을 정리한다.
2. 게이트웨이가 검증한 사용자 정보를 헤더로 받을 수 있게 한다.
3. 도메인별 테이블 소유 관계를 확정한다.
4. 서비스별 담당 팀과 기술 스택을 정하고, 새 모노레포 구조에서 서비스를 다시 구성한다.
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

## 서비스 구성과 기술 스택

기술 스택은 서비스별 부하 특성과 담당 팀의 운영 경험을 기준으로 정했다.
트래픽이 몰리는 실시간·인증 경로는 가벼운 런타임을 우선했고, 기존 도메인 로직을 옮기는 서비스는 Spring Boot를 유지했다.

| 서비스 | 역할 | 기술 스택 |
|---|---|---|
| cowork-gateway | 라우팅, JWT 검증 | Spring Cloud Gateway (Kotlin) |
| cowork-config | 설정 서버, 서비스 디스커버리 | Spring Cloud Config, Eureka (Kotlin) |
| cowork-authorization | DataGSM 로그인, 토큰 발급 | Go |
| cowork-user | 사용자 프로필 | Elixir |
| cowork-team | 팀, 팀 멤버 | Spring Boot (Kotlin) |
| cowork-project | 프로젝트 | Spring Boot (Kotlin) |
| cowork-channel | 채널, 웹훅, 스레드, 계정 공유, 회의록 | Spring Boot (Kotlin) |
| cowork-preference | 설정 | Vert.x (Kotlin) |
| cowork-chat | 채팅 | NestJS (TypeScript) |
| cowork-voice | 음성 | Go |
| cowork-notification | 푸시, SSE 알림 | Go |

모놀리식 코드는 새 구조로 옮긴 뒤 저장소에서 제거한다.
