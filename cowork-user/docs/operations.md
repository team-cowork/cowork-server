# cowork-user 운영 절차

## 마이그레이션

스키마는 `src/db/migration`의 `V{n}__*.sql`을 공식 Flyway CLI(`flyway/flyway`)가 적용하고, 앱은 마이그레이션을 실행하지 않습니다. 접속 정보는 앱과 같은 `DB_HOST`·`DB_PORT`·`DB_NAME`·`DB_USERNAME`·`DB_PASSWORD`를 받고, JDBC로의 변환은 `src/db/flyway.toml`이 맡습니다.

- 로컬 Compose: `cowork-user-migrate`가 MySQL이 준비된 뒤 한 번 실행되고, `cowork-user`는 그 완료를 기다립니다.
- 운영 CD: `deploy/prod/lib/migration.sh`가 새 이미지의 `/app/db`를 꺼내 컨테이너 전환 전에 `flyway migrate`를 실행합니다. 이전 컨테이너가 서비스하는 동안 적용하고, 실패하면 배포를 중단합니다. 스키마는 되돌리지 않으므로 이전 컨테이너가 새 스키마에서도 동작하도록 컬럼은 먼저 추가하고 나중에 제거합니다.

## 프로젝션 재구축

체크포인트와 프로젝션(`tb_team_member_projections`, `tb_user_presence_projections`)은 같은 MySQL 트랜잭션에 기록하므로 한 저장소로 함께 움직입니다. 시작할 때 브로커의 토픽 UUID가 `tb_kafka_projection_generations.topic_id`와 같다는 것을 증명하지 못하면(UUID가 다르거나 저장된 값이 없거나 브로커가 UUID를 보고하지 않으면) 해당 프로젝션과 체크포인트·마커를 같은 트랜잭션에서 지운 뒤 새 세대로 재생합니다. 실행 중에는 매 readiness 확인에서 브로커가 보고한 UUID가 저장된 값과 달라지면 같은 절차로 재구축합니다. `custom_status`·`status_message`·`status_expires_at`은 사용자 소유라 보존하고, presence가 반영된 계정은 `offline`으로 되돌려 재생 결과로 다시 채웁니다.

원본 서비스(`cowork-team`, `cowork-authorization`)의 데이터를 교체하거나 되돌릴 때는 삭제 이력이 사라지므로, 해당 토픽도 함께 교체해 UUID가 바뀌게 합니다. 보존이 필요하면 교체 전에 미발행 outbox와 삭제 이력을 옮겨 둡니다.

## 프로필 토픽 v2 전환

`user.profile.event.v2`는 파티셔너를 라이브러리 기본(`Murmur2Random`)으로 바꾸면서 올린 새 버전입니다. 순서는 다음과 같습니다.

1. v2 토픽을 컴팩트 보존으로 만듭니다. 로컬은 `kafka-init`이, 운영은 IaC 적용(`deploy/iac/shared/runtime.py`)이 `deploy/compose/stack.yaml`의 `kafka-init` 토픽 카탈로그를 읽어 없는 토픽을 만듭니다.
2. `cowork-user`를 배포합니다. 읽기 모델이 준비되면 전체 프로필 스냅샷과 파티션별 완료 마커를 v2에 발행합니다.
3. 소비자(`cowork-notification`, `cowork-project`, `cowork-chat`)를 배포합니다. 새 토픽은 체크포인트가 없어 처음부터 읽습니다. `cowork-chat`은 저장된 `userProfile` dataset이 선언된 이전 정체성(`user.profile.event`, `cowork-chat-user-profile-event-v1-projection`)이면 시작할 때 스스로 rebuild를 요청하므로 별도 요청이 필요 없습니다. `GET /admin/projections`에서 `userProfile`이 `ACTIVE`로 돌아왔는지 확인합니다. 선언되지 않은 정체성 변경은 `REBUILD_REQUIRED`로 남고, 관리자 권한의 `POST /admin/projections/userProfile/rebuild`(본문 `{"reason": "..."}`)로 요청해야 합니다.
4. 구 토픽 `user.profile.event`는 카탈로그에서 빠졌지만 브로커에서 지워지지는 않습니다. producer가 모두 멈추고 소비자가 준비된 뒤에 정리합니다.
