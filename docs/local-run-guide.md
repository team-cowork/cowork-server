# 로컬 첫 기동

Docker Compose `2.24.4` 이상을 사용한다. 기존 데이터를 유지하는 상태 토픽 전환은
[별도 절차](./kafka-state-topic-cutover.md)를 따른다.

## 준비와 실행

저장소 루트에서 `.env.example`을 `.env`로 복사하고 값을 채운다. 필수값은
[`deploy/local/stack.sh`](../deploy/local/stack.sh)의 `REQUIRED_VARS`를 기준으로 한다.
암호화·서명 키는 서로 다른 값으로 생성한다.

```bash
cp .env.example .env
openssl rand -base64 32
python3 deploy/config/generate-control-plane-credentials.py --profile local --output deploy/local/secrets/config-access
python3 deploy/validate.py
./deploy/local/stack.sh start
```

계정 생성기는 기존 출력 디렉터리를 덮어쓰지 않는다. 이미 생성한 계정은 다시 사용하며,
교체는 [Config Server 접근 보호](./config-server-access.md)를 따른다.
직접 Compose를 실행할 때는 생성한 계정 파일도 읽는다.

```bash
docker compose --env-file .env --env-file deploy/local/secrets/config-access/compose.env config --quiet
docker compose --env-file .env --env-file deploy/local/secrets/config-access/compose.env up -d
```

빈 Vault의 notification은 Firebase credential을 등록하기 전까지 종료한다.
Vault UI `http://localhost:8200`에서 `secret/cowork-notification/local`의
`fcm.credentials-json`에 서비스 계정 JSON 전체를 문자열로 등록한 뒤 아래 명령으로 재기동한다.
기존 프로파일 속성은 보존한다.

```bash
docker compose restart cowork-notification
```

## 기동 완료 판단

`stack.sh`의 종료는 core infra의 health 확인까지다. 전체 앱 readiness는 별도로 확인한다.
init job의 `Exited (0)`과 장기 실행 컨테이너의 health를 구분한다.

```bash
docker compose ps --all
docker compose logs -f --tail=200
```

빈 DB에서도 모든 필수 state topic partition의 `PROJECTION_SNAPSHOT_COMPLETED`와 checkpoint
catch-up이 필요하다. upstream snapshot을 기다리는 동안 앱이 `starting`일 수 있다.
user는 authorization의 presence와 team membership을, project·chat은 channel·user·preference·
GitHub repository 등 각자의 필수 projection을 확인한다. broker 연결만으로 기동 완료를 판정하지 않는다.

init job 실패는 해당 job 로그부터 확인한다. projection 대기는 source snapshot, relay backlog,
consumer checkpoint·격리 상태를 확인한다. 로그인만 실패하면 `user.identity.command`·
`user.identity.command-result`와 양쪽 operation·inbox·outbox를 함께 확인한다.
설정 조회의 인증 실패는 생성한 bootstrap 계정이 해당 앱에 공급됐는지 확인한다.

## 외부 연동과 실기기

별도 `cowork-github-app`은 Compose에 포함되지 않는다. GitHub API까지 확인할 때 별도로 실행하고
내부 API key를 양쪽에 맞춘다. `github.pr.merge`·`github.pr.approve`는 기본 provisioning에 없으므로
해당 API를 사용할 환경에서 명시적으로 준비한다.

`stack.sh`는 S3 공개 주소의 `__LOCAL_IP__`를 LAN IP로 치환한다. 직접 Compose를 실행하면
이 치환이 없으므로 실기기용 주소를 `.env`에 지정한다. `LIVEKIT_WS_URL`도 실기기에서 도달 가능한
LAN 주소로 설정한다. [S3 접근 정책](./todo/items/13-storage/object-storage-public-access-contract.md)은 아직 미확정이다.

로컬 데이터를 모두 버리는 재초기화가 필요할 때만 `docker compose down -v --remove-orphans`를
실행한다. 이 명령은 DB·Kafka·파일 볼륨을 삭제한다.
