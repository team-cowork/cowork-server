# cowork-preference

## 역할

사용자·팀·채널의 설정과 사용자 정의 역할 정책을 전담 관리합니다.

- 사용자 상태·표시 설정, 팀·채널 설정과 채널별 알림 설정
- 프로젝트 역할과 팀 사용자 정의 역할·권한·멤버 할당
- 채널별 역할의 메시지 읽기 정책과 GitHub 저장소 라벨 정책
- 설정 캐시·상태 만료 처리와 변경 이벤트 발행

## 스택

- Kotlin / Java 25 / Vert.x Coroutines
- Amper (`module.yaml`)
- Vert.x PostgreSQL Client / PostgreSQL / Flyway
- Vert.x Redis Client / Kafka Client / Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `9001`        | `9001`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수                     | 기본값                      | 설명                                |
|--------------------------|-----------------------------|-------------------------------------|
| `SPRING_PROFILES_ACTIVE` | `local`                     | 설정 프로파일 (`local` 또는 `prod`) |
| `CONFIG_SERVER_URL`      | `http://cowork-config:8761` | 필수 Config Server 연결             |

- Config Server: 포트, PostgreSQL host·DB·schema·pool, Redis, Kafka, Eureka.
- Vault: `preference.db.username`, `preference.db.password`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 변경 절차](../docs/deployment.md#설정-변경과-재배포)를 참고합니다.

Config Server를 3회 조회하지 못하면 종료합니다. Preference command·state·result 토픽 이름은 서비스 간 계약이므로 환경별 override를 허용하지 않습니다.

## Kafka 계약

| 소비 토픽                                | Consumer group                                   | 용도                                               |
|------------------------------------------|--------------------------------------------------|----------------------------------------------------|
| `team.member.event`                      | `cowork-preference-team-member-projection`       | 팀 멤버 projection, 팀 삭제·멤버 탈퇴 시 역할 정리 |
| `channel.event.v2`                       | `cowork-preference-channel-lifecycle-projection` | 채널 삭제 fence, 삭제 채널의 역할 정책 정리        |
| `preference.team-role.command`           | `cowork-preference-team-role-command`            | 사용자 정의 팀 역할·할당 command                   |
| `preference.channel-role-policy.command` | `cowork-preference-channel-role-policy-command`  | 채널별 역할 정책 command                           |
| `preference.github-repo.setting.command` | `cowork-preference-github-repo-setting-command`  | GitHub 저장소 설정 command                         |

- `/health/ready`와 Eureka 등록은 `team.member.event` projection이 현재 generation의 snapshot 완료 marker와
  시작 시 high-watermark까지 따라잡은 뒤에 열립니다. HTTP API는 채널 projection을 읽지 않으므로
  `channel.event.v2` projection은 트래픽 readiness에 포함하지 않습니다.
- 채널 역할 정책 command는 `team.member.event`, `channel.event.v2` projection이 모두 준비되어야 처리합니다.
- 채널 역할 정책 command는 채널 projection row를 잠근 뒤 처리합니다. 채널 삭제가 먼저 반영됐으면 `UPSERT`와
  `DELETE` 모두 `CHANNEL_DELETED`, 채널이 요청 팀에 속하지 않으면 `CHANNEL_TEAM_MISMATCH` 실패 결과로
  종료합니다. 채널 projection이 아직 없으면 결과를 남기지 않고 재시도합니다.
- `preference.channel-role-policy.changed` snapshot은 두 upstream projection이 준비된 뒤, 나머지 state snapshot은
  `team.member.event` projection이 준비된 뒤 발행합니다.
