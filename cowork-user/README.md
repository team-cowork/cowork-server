# cowork-user

## 역할

사용자 계정·공개 프로필의 원본 데이터를 관리합니다.

- 로그인 요청에 따른 계정·프로필 생성·동기화와 DataGSM 정보 갱신
- 프로필 조회·수정, 사용자 검색과 상태 메시지 관리
- 프로필 이미지 업로드·삭제와 온라인·오프라인 상태 반영

## 스택

- C# / ASP.NET Core (.NET 10)
- .NET CLI (`dotnet test cowork-user.slnx`)
- MySqlConnector · Dapper / MySQL (마이그레이션은 공식 Flyway CLI)
- Confluent.Kafka / StackExchange.Redis / AWS SDK S3 / SeaweedFS
- Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8082`        | `8082`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_CONFIG_PROFILE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_USER_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_USER_PASSWORD`에서 전달 |

운영 검색은 `utf8mb4_unicode_ci`와 `NO_BACKSLASH_ESCAPES`가 없는 세션에 의존합니다.

## 마이그레이션

스키마는 `src/db/migration`의 `V{n}__*.sql`을 공식 Flyway CLI(`flyway/flyway`)가 적용하고, 앱은 마이그레이션을 실행하지 않습니다. 접속 정보는 앱과 같은 `DB_HOST`·`DB_PORT`·`DB_NAME`·`DB_USERNAME`·`DB_PASSWORD`를 받고, JDBC로의 변환은 `src/db/flyway.toml`이 맡습니다.

- 로컬 Compose: `cowork-user-migrate`가 MySQL이 준비된 뒤 한 번 실행되고, `cowork-user`는 그 완료를 기다립니다.
- 운영 CD: `deploy/prod/lib/migration.sh`가 새 이미지의 `/app/db`를 꺼내 전환 전에 `flyway migrate`를 실행합니다. 이전 컨테이너가 서비스하는 동안 적용하고, 실패하면 배포를 중단합니다. 스키마는 되돌리지 않으므로 이전 컨테이너가 새 스키마에서도 동작하도록 컬럼은 먼저 추가하고 나중에 제거합니다.

## 프로젝션 재구축

체크포인트와 프로젝션(`tb_team_member_projections`, `tb_user_presence_projections`)은 같은 MySQL 트랜잭션에 기록하므로 한 저장소로 함께 움직입니다. 시작할 때 브로커의 토픽 UUID가 `tb_kafka_projection_generations.topic_id`와 같다는 것을 증명하지 못하면(UUID가 다르거나 저장된 값이 없거나 브로커가 UUID를 보고하지 않으면) 해당 프로젝션과 체크포인트·마커를 같은 트랜잭션에서 지운 뒤 새 세대로 재생합니다. 실행 중에는 매 readiness 확인에서 브로커가 보고한 UUID가 저장된 값과 달라지면 같은 절차로 재구축합니다. `custom_status`·`status_message`·`status_expires_at`은 사용자 소유라 보존하고, presence가 반영된 계정은 `offline`으로 되돌려 재생 결과로 다시 채웁니다.

원본 서비스(`cowork-team`, `cowork-authorization`)의 데이터를 교체하거나 되돌릴 때는 삭제 이력이 사라지므로, 해당 토픽도 함께 교체해 UUID가 바뀌게 합니다. 보존이 필요하면 교체 전에 미발행 outbox와 삭제 이력을 옮겨 둡니다.

## 프로필 토픽 v2 전환

`user.profile.event.v2`는 파티셔너를 라이브러리 기본(`Murmur2Random`)으로 바꾸면서 올린 새 버전입니다. 순서는 다음과 같습니다.

1. 브로커에 `user.profile.event.v2`를 컴팩트 보존으로, 기존 토픽과 같은 파티션 수로 만듭니다. 로컬은 `kafka-init`이 만듭니다.
2. `cowork-user`를 배포합니다. 읽기 모델이 준비되면 전체 프로필 스냅샷과 파티션별 완료 마커를 v2에 발행합니다.
3. 소비자(`cowork-notification`, `cowork-project`, `cowork-chat`)를 배포합니다. 새 토픽은 체크포인트가 없어 처음부터 읽습니다. 다만 `cowork-chat`은 저장된 `userProfile` dataset의 토픽·그룹이 달라 `REBUILD_REQUIRED`로 readiness를 닫으므로, 관리자 권한으로 `POST /admin/projections/userProfile/rebuild`(본문 `{"reason": "..."}`)를 요청하고 `GET /admin/projections`에서 `userProfile`이 `ACTIVE`로 돌아왔는지 확인합니다.
4. 구 토픽 `user.profile.event`는 producer가 모두 멈추고 소비자가 준비된 뒤에 정리합니다.
