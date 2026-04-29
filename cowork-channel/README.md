# cowork-channel

## 역할
팀 내 채널 관리 서비스.

- 채널 생성/수정/삭제 (`type`은 TEXT/VOICE, `view_type`은 텍스트 외에 WEBHOOK/ACCOUNT_SHARE/FILE_SHARE/MEETING_NOTE 등 표현 분기)
- 채널 멤버 관리 (공개 채널은 팀 멤버 누구나 합류, 비공개 채널은 채널 생성자/팀 OWNER·ADMIN만 추가 가능)
- TEXT 채널 한정 웹훅/스레드 관리
- 팀/유저 라이프사이클 이벤트(Kafka) 수신 후 채널·멤버십 정리

## 스택
- Spring Boot 3 / Kotlin / Java 21
- Spring Data JPA + MySQL, Flyway
- Spring Cloud (Eureka, Config, OpenFeign + Resilience4j)
- Spring Kafka (consumer, DLQ)

## 포트
`8083`

## DB
MySQL — Flyway 관리.
- `V1__init.sql` (immutable, 기존 스키마 유지)
- `V2__align_with_spec.sql` 에서 `tb_channels`에 `view_type/description/is_private/created_by` 추가, `tb_channel_members/tb_webhooks/tb_threads` 신설, 기존 `tb_channel_roles` 제거

## 권한 모델
- 채널 수정/삭제: 채널 생성자 OR 팀 OWNER/ADMIN
- 멤버 추가: 공개 채널은 팀 멤버, 비공개는 채널 관리자
- 멤버 제거: 본인 OR 채널 생성자 OR 팀 OWNER/ADMIN (단, 채널 생성자는 본인 제거 불가)
- 웹훅: TEXT 채널에서만, 채널 생성자 OR 팀 OWNER/ADMIN
- 스레드 수정: 작성자 OR 채널 생성자 OR 팀 OWNER/ADMIN

팀 권한은 `cowork-team` Feign 호출(`TeamClient.getMembership`)로 조회하며, Resilience4j 서킷브레이커가 열리면 fail-closed.

## Kafka
구독 토픽:
- `team.lifecycle` — `TEAM_DELETED` / `MEMBER_REMOVED` 처리
- `user.lifecycle` — `USER_DELETED` 처리

처리 실패 시 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`로 `<topic>.DLT`에 적재.
