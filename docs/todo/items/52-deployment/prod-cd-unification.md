# 운영 서비스의 CD 배포 일원화

- **서비스**: cowork-team, cowork-user, cowork-notification, cowork-voice, cowork-authorization, cowork-chat, Vault, GitHub Actions
- **우선순위**: 🔴 높음
- **현재 상태**: 2026-10-02 VM 재부팅 장애를 수동 컨테이너로 복구했고, 4개 대상은 Vault 배포 문서가 없어 CD로 배포할 수 없다
- **관련 작업**: [CD health 대기와 SSH 명령 제한 분리](../53-deployment/cd-health-wait-ssh-timeout.md), [notification FCM 자격 증명 Vault 등록](../55-configuration/notification-fcm-credentials.md)

## 진행 상태 (2026-10-02)

VM 재부팅 후 Vault가 sealed 상태가 되어 Config Server가 500을 반환했고, 대부분의 서비스가 기동하지 못했다. 아래는 당일 복구 결과다.

| 서비스 | VM | 컨테이너 출처 | 복구 내용 | 상태 |
|---|---|---|---|---|
| Vault | cowork-db | CD | `vault_recovery` 실행으로 unseal | 정상 |
| config, gateway | cowork-db | CD | Vault unseal 후 자동 회복 | 정상 |
| preference | preference VM | 수동 | `SPRING_PROFILES_ACTIVE=prod`, `EUREKA_SERVER_URL` 지정 후 재생성, 기존 컨테이너는 `cowork-preference-old` | 정상 |
| team | cowork-db | 수동 `cowork-team:local` | `docker network connect cowork-server_default cowork-config`로 `cowork-config` 이름 해석 복구 | 정상, local 프로파일 유지 |
| authorization | authorization VM | 수동 `cowork-authorization:local` | `APP_PROFILE=prod`와 주소 env로 재생성, 기존 컨테이너는 `cowork-authorization-old` | 정상 |
| user | user VM | 수동, GHCR `sha-231bfea…` | 빈 VM에 Docker 설치 후 prod 프로파일로 생성 | Kafka projection 재생 중 |
| notification | notification VM | 수동 `cowork-notification:local` | prod 설정 로드까지 확인, FCM 자격 증명 부재로 중지 | 중지 |
| chat | 미확인 | CD | `check_only=false` 배포가 SSH `Run Command Timeout`으로 중단 | 미확인 |
| voice | voice VM | 수동 `ghcr.io/verify-test/cowork-voice:sha-testrun` | 원인만 확인 | 재시작 반복 |
| channel, project, roadmap | — | — | 별도 조치 없음 | 정상 |

수동 복구에 사용한 env 파일 `~/pref.env`, `~/auth.env`, `~/noti.env`가 각 VM에 남아 있을 수 있다. `~/auth.env`, `~/noti.env`에는 DB 비밀번호가 들어 있다.

## 문제

CD는 Vault KV `secret/data/deploy/<target>` 문서를 읽어 SSH 대상과 런타임 값을 얻는다. 2026-10-02 `check_only` 실행 결과 `deploy/channel`과 `deploy/chat`은 존재하지만, `deploy/team`, `deploy/user`, `deploy/notification`, `deploy/voice`는 HTTP 404를 반환했다. 이 대상들은 CD로 배포할 수 없어 운영자가 VM에서 직접 빌드한 `:local` 이미지나 출처가 불분명한 테스트 이미지로 운영되어 왔다. `deploy/prod/inventory.json`에는 `log-agent`도 없다.

수동 컨테이너는 CD가 넣는 환경변수(`deploy/prod/lib/environment.sh`의 `custom_environment`, `spring_environment`)를 갖고 있지 않다. 운영 설정 파일(`cowork-config/src/main/resources/configs/*-prod.yml`)에는 `kafka:9092`, `http://cowork-config:8761/eureka`, `mysql` 같은 Docker 내부 이름이 들어 있다. 그래서 프로파일이나 주소 env가 빠지면 기동에 실패한다. Config Server의 Vault 토큰은 `secret/data/cowork-*/local` 경로를 거부하므로, 프로파일이 기본값 `local`로 남은 Go·Elixir 서비스(`APP_PROFILE`)는 모두 500을 받았다.

team의 `cowork-config` 이름 해석은 cowork-db에서 수동으로 연결한 Docker 네트워크에 의존한다. CD가 config를 다시 배포하면 이 연결은 사라진다. team VM과 user VM에는 장애 시점에 Docker도 설치되어 있지 않았다.

## 할 일

### Vault 배포 문서

- `deploy/team`, `deploy/user`, `deploy/notification`, `deploy/voice` 문서를 `deploy/prod/settings.example.json` 형식으로 작성한다.
- 문서는 `operation=update-config`, `scope=deployment`, `expected_version=0`과 `VAULT_UPDATE_JSON` secret으로 생성한다.
- team의 실행 VM을 cowork-db과 team VM 중 하나로 결정해 `ssh.host`에 반영한다.

### CD 전환

- 각 대상을 `check_only=true`로 검증한 뒤 `check_only=false`로 배포한다.
- CD 배포가 끝나면 `*-old` 컨테이너와 수동 `:local` 컨테이너를 제거한다.
- voice VM의 `verify-test` 이미지 컨테이너를 CD 배포 이미지로 교체한다.
- cowork-db의 `cowork-config`에 수동 연결한 `cowork-server_default` 네트워크를 team CD 전환 후 끊는다.
- `log-agent`를 `deploy/prod/inventory.json`에 포함할지 결정한다.

### 정리

- 각 VM에 남은 `~/pref.env`, `~/auth.env`, `~/noti.env`, `~/user.env`를 `shred -u`로 삭제한다.
- chat의 실행 VM과 현재 컨테이너 상태를 확인한다.

## 검증

- 모든 대상에 대해 `check_only=true` 실행이 Vault 404 없이 통과한다.
- `http://<cowork-db>:8761/eureka/apps`에 12개 애플리케이션 서비스가 `UP`으로 등록된다.
- 각 VM의 `docker ps`에 `ghcr.io/team-cowork/cowork-<service>:sha-<40자리 SHA>` 이미지만 남는다.

## 완료 조건

- 모든 운영 대상의 `deploy/<target>` 문서가 Vault에 존재한다.
- 운영 VM에 수동 빌드 이미지, 테스트 레지스트리 이미지, `*-old` 컨테이너가 남아 있지 않다.
- 각 서비스의 마지막 성공 배포가 GitHub Deployment `cowork-runtime` 기록과 일치한다.
