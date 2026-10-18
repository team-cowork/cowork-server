# cowork-user

## 역할

사용자 계정·공개 프로필의 원본 데이터를 관리합니다.

- 로그인 요청에 따른 계정·프로필 생성·동기화와 DataGSM 정보 갱신
- 프로필 조회·수정, 사용자 검색과 상태 메시지 관리
- 프로필 이미지 업로드·삭제와 온라인·오프라인 상태 반영

## 스택

- C# / ASP.NET Core (.NET 10)
- .NET CLI (`dotnet test cowork-user.slnx`)
- MySqlConnector · Dapper / MySQL (Flyway 호환 마이그레이션 이력)
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

## 프로젝션 재구축

체크포인트와 프로젝션(`tb_team_member_projections`, `tb_user_presence_projections`)은 같은 MySQL 트랜잭션에 기록하므로 한 저장소로 함께 움직입니다. 시작할 때와 실행 중 매 readiness 확인에서 브로커의 토픽 UUID를 `tb_kafka_projection_generations.topic_id`와 비교하고, 일치를 증명할 수 없으면 해당 프로젝션과 체크포인트·마커를 같은 트랜잭션에서 지운 뒤 새 세대로 재생합니다. `custom_status`·`status_message`·`status_expires_at`은 사용자 소유라 보존하고, presence가 반영된 계정은 `offline`으로 되돌려 재생 결과로 다시 채웁니다.

원본 서비스(`cowork-team`, `cowork-authorization`)의 데이터를 교체하거나 되돌릴 때는 삭제 이력이 사라지므로, 해당 토픽도 함께 교체해 UUID가 바뀌게 합니다. 보존이 필요하면 교체 전에 미발행 outbox와 삭제 이력을 옮겨 둡니다.
