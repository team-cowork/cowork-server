# DataGSM SDK 전환과 기존 경로 폐기

## 배포 전 확인

DataGSM Go SDK v0.2.0의 `oauth`와 `event`를 사용한다. 기존 `/auth/token` 요청 형식은 유지한다. 클라이언트 저장소 수정은 이 변경의 범위에 포함하지 않는다.

1. GitHub Variable `DATAGSM_REDIRECT_URIS`에 DataGSM 관리 화면의 웹·모바일·데스크탑 콜백을 정확히 등록한다. 쉼표로 구분하며, 실제 운영 웹 콜백의 전체 경로를 확인한다. 허용 목록이 없으면 서버 시작에 실패한다. `cowork://oauth/callback`과 등록된 loopback HTTP 콜백도 포함할 수 있다.
2. 새 로그인 흐름에 필요한 `openid datagsm:account_read datagsm:student_read` scope를 DataGSM 앱에서 사용할 수 있는지 확인한다. 기존 클라이언트의 scope 설정을 이 서버 변경에서 수정하지 않는다.
3. Kafka 정책을 먼저 적용해 `user.identity.command.v2`와 양쪽 서비스의 ACL을 준비한다. 기존 토픽과 ACL은 유지한다.
4. v1·v2 소비를 지원하는 `cowork-user`를 먼저 배포하고 소비 상태를 확인한다. 다음으로 authorization과 Gateway를 배포한다. 동시 배포 중 v2 소비자가 아직 준비되지 않으면 로그인 결과 대기가 실패할 수 있다.
5. 토픽 환경변수 override가 있다면 authorization과 user의 command topic을 모두 `user.identity.command.v2`로 맞춘다. 새 consumer group은 `cowork-user.user-identity-command.v2`다. 구형 consumer는 배포된 기본 토픽 `user.identity.command`와 그룹 `cowork-user.user-identity-command`를 유지한다. 운영에서 다른 구형 값을 사용했다면 해당 소비부를 배포 전에 맞춘다.

`major`는 선택 값으로 전달한다. 사용자 DB 컬럼은 이미 nullable이므로 DB migration은 필요하지 않다. wire contract의 변경은 command schema 2와 새 토픽으로 구분한다. 결과 contract는 schema 1이며, 기존 result·result-DLT 토픽을 계속 사용한다.

새로 로그인할 때 DataGSM 계정 역할이 `ADMIN` 또는 `USER`이면 Cowork 플랫폼 역할은 모두 `MEMBER`로 발급한다. 기존 `/auth/token`을 통한 로그인에도 적용한다. 기존 세션과 refresh로 이어지는 세션의 역할은 변경하지 않는다.

## 클라이언트가 추후 전환할 흐름

Gateway의 API prefix는 `/api/authorization`이다.

1. `POST /auth/authorize`에 `{"redirect_uri":"등록된 콜백 URL"}`을 전송한다. 응답의 `authorization_url`로 이동하고 로그인 시도 쿠키를 유지한다.
2. DataGSM 콜백의 `state`와 `code`를 `POST /auth/callback`으로 전달한다. 사용자가 거절하면 `state`와 `error`를 전달한다. 시작 때 받은 쿠키가 필요하다.
3. 서버는 저장한 state·nonce·PKCE verifier, ID token 서명·issuer·audience·만료, UserInfo subject를 확인한 후 기존 Cowork 토큰 쌍을 발급한다. 로그인 시도는 성공 여부와 관계없이 소비 후 재사용할 수 없다. 실패하면 새 시도를 시작한다.

ID token의 `iat`·`nbf`·`exp`에는 서버에서 30초의 시계 오차를 허용한다. 현재 시각에서 30초 이내인 토큰 유효 구간의 가장 가까운 시각으로 SDK 검증을 수행한다. SDK 수정 없이 서명과 나머지 클레임 검증을 유지한다. 각 로그인 시도는 state별 쿠키를 사용하므로 여러 탭에서 시작한 시도가 서로의 쿠키를 덮어쓰지 않는다.

두 새 API는 JSON 요청을 받는다. 웹은 요청에 credentials를 포함하고 Gateway CORS가 해당 origin을 허용해야 한다. 브라우저 origin은 등록된 HTTP(S) 콜백의 origin과 일치해야 한다. 쿠키는 `Secure`, `HttpOnly`, `SameSite=None`이므로 API 접속에는 HTTPS가 필요하며 브라우저의 타사 쿠키 정책도 확인해야 한다. 서버 프록시를 사용하는 웹은 Set-Cookie와 이후 Cookie 전달을 보존해야 한다. 네이티브 앱도 시작과 완료 요청 사이에 쿠키 저장소를 유지해야 한다. 등록된 HTTP localhost 콜백은 DataGSM 리다이렉트 수신 주소이며 API의 HTTPS 요구와 별개다.

기존 `/auth/token`은 클라이언트가 만든 `code`, `code_verifier`, `redirect_uri`를 받는 호환 경로다. 새 서버 관리 시도는 verifier를 클라이언트에 제공하지 않으며 이 경로로 전환하지 않는다. SDK의 provider refresh/logout은 Cowork 세션 갱신·로그아웃과 다르므로 기존 Cowork 세션 처리를 유지한다. OpenAPI를 통한 전체 명부 조회는 별도 자격증명과 기능 범위가 필요해 이 로그인·웹훅 전환에 포함하지 않는다.

## 기존 로그인 API 폐기

웹·모바일 구현의 전환과 배포가 완료된 뒤 진행한다. 데스크탑을 포함한 실제 호출 주체가 `/auth/token`을 더 이상 사용하지 않는지도 확인한다. SDK 의존성은 새 로그인과 웹훅에 계속 필요하다.

<!-- TODO(legacy-login): Remove the legacy endpoint instructions and this checklist after the web and mobile implementations migrate and all /auth/token callers stop. -->

`TODO(legacy-login)`으로 아래 수정 지점을 찾을 수 있다. 경로는 저장소 루트 기준이다.

| 위치 | 제거·갱신할 항목 |
| --- | --- |
| `cowork-authorization/cmd/main.go` | `/token` 라우트 등록 |
| `cowork-authorization/internal/handler/auth_handler.go` | `Token`, `CodeExchangeRequest`, 관련 Swagger 주석과 의도적 deprecated 호출의 lint 예외 |
| `cowork-authorization/internal/service/auth_service.go` | `ExchangeCode` |
| `cowork-authorization/internal/service/datagsm_login.go` | 호환 전용 `Exchange` |
| `cowork-authorization/internal/service/datagsm_login_test.go` | `TestLegacyPKCEExchangeUsesSDK` |
| `cowork-gateway/src/main/kotlin/com/cowork/gateway/security/config/SecurityConfig.kt` | 기존 token 경로의 익명 접근 허용 |
| `cowork-gateway/src/main/resources/application-prod.yml` | 공용 rate-limit route의 기존 token 경로 |
| `cowork-authorization/swagger/{docs.go,swagger.json,swagger.yaml}` | `make swagger-gen`으로 endpoint·request schema 삭제 반영 |

Swagger는 자동 생성 파일이므로 직접 TODO를 추가하지 않는다. 생성 원본에 재생성 TODO를 둔다. 새 authorize/callback, refresh, 공용 세션 발급, SDK 의존성은 계속 사용한다.

## 구형 Kafka 토픽 폐기

로그인 API의 폐기와 토픽의 폐기는 별도 조건으로 판단한다. 새 서버는 기존 `/auth/token` 요청도 v2 command로 발행한다. 따라서 웹·모바일이 기존 API를 사용하는 동안에도 이전 서버 producer와 backlog가 모두 사라지면 v1 토픽을 폐기할 수 있다.

저장소의 `.claude/rules/kafka-projections.md`는 producer 중지, pending outbox 처리, 대체 consumer 준비를 요구한다. `deploy/runtime/kafka/server.properties`는 `delete.topic.enable=true`다. `initialize.py`는 선언된 토픽을 생성·설정하고 ACL을 조정하지만 선언에서 빠진 broker 토픽은 삭제하지 않는다. 선언을 남긴 채 삭제하면 다음 정책 적용에서 토픽이 재생성된다. 애플리케이션 ACL에는 DELETE 권한이 없다.

<!-- TODO(legacy-identity-topic): Remove this v1 retirement checklist after the old topic and its compatibility code are retired. Preserve any still-needed rollout and historical inbox hash notes. -->

1. 모든 authorization 인스턴스가 v2로 발행하는지 확인한다. 이전 이미지, 별도 producer, 재시작할 구형 job과 rollback 대상도 점검한다. v1 producer를 다시 배포하지 않도록 한다.
2. authorization DB의 구형 토픽 outbox가 0인지 확인한다. relay는 성공한 행을 삭제하므로 남아 있는 행이 미전달 항목이다. 실패 행을 임의로 지우지 말고 원인을 해결해 전달한다.

   ```sql
   SELECT COUNT(*) AS pending_legacy_commands
   FROM tb_kafka_outbox
   WHERE topic = 'user.identity.command';
   ```

3. 구형 group `cowork-user.user-identity-command`의 모든 partition lag가 0이고 추가 발행이 없는지 확인한다. lag 0만으로 성공을 판정하지 않는다. 구형 command의 quarantine·실패 결과를 확인하고, 전환 전 접수된 `tb_user_identity_operations`의 `PENDING` 요청과 결과 outbox도 해소한다. 실제 전환 시각을 기록해 새 요청과 구분한다.
4. 새 group `cowork-user.user-identity-command.v2`가 정상 소비하고 실제 로그인과 `major=null` 처리 결과가 정상인지 확인한다.
5. `TODO(legacy-identity-topic)`이 있는 `Consumers.cs`의 v1 consumer, `IdentityCommand.cs`의 compatibility 인자, `IdentityCommandContract.cs`의 v1 parser·major 필수 분기, `KafkaPolicyTests.cs`의 관련 fixture·검증을 정리해 배포한다. 저장된 inbox가 사용하는 과거 hash version 처리와 fixture는 토픽 삭제만으로 제거하지 않는다.
6. `deploy/runtime/kafka/policy.json`의 `topics["user.identity.command"]`, `principals["cowork-authorization"].topics`, `principals["cowork-user"].topics`에 있는 구형 토픽 항목을 삭제하고 정책을 재적용한다. JSON은 주석을 지원하지 않으므로 이 세 위치의 TODO는 해당 파일을 읽는 `initialize.py`에도 기록한다. result, result-DLT, v2 command 토픽은 유지한다.
7. 운영자가 broker 내부 listener에 접근 가능한 Kafka allocation에서 구형 토픽만 삭제한다. 다음은 확인·삭제 명령 예시이며 배포 스크립트에서 자동 실행하지 않는다.

   ```sh
   /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 \
     --describe --group cowork-user.user-identity-command
   /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 \
     --delete --topic user.identity.command
   /opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list
   ```

8. 삭제 완료와 이후 정책 적용 시 재생성되지 않음을 확인한다. 해당 토픽은 command stream이므로 사용자 owner 데이터나 다른 state projection checkpoint를 지우지 않는다.

구형 토픽을 삭제한 뒤에는 v1 전용 서버로 단순 rollback할 수 없다. 새 서버의 v2 outbox도 구형 consumer가 처리할 수 없으므로, 전환 중 rollback에서도 v2 consumer를 유지해 이미 접수한 요청을 처리해야 한다.

## 참고 문서

- [DataGSM 문서 인덱스](https://docs.datagsm.kr/llms.txt)
- [DataGSM Go SDK v0.2.0](https://github.com/tmp-it-play/datagsm-sdk-go/tree/v0.2.0)
