# 홈서버(맥 미니) 단독 배포 — cowork-project

`kimtaeeun.site`를 서비스하는 맥 홈서버에 `cowork-project`만 독립 환경으로 배포하는 절차다.
user/preference는 이미 다른 환경에 배포되어 있지만 그쪽 Kafka/Eureka/Config 접속 정보가
아직 공유되지 않아, 이번 배포는 project 전용 mysql/kafka/vault/config-server를 새로
띄우는 **독립 구성**이다.

## 알려진 제약사항

- `cowork-project`는 Kafka에서 `team.lifecycle`, `user.lifecycle` 이벤트를 소비하는데,
  team/user 서비스가 이 환경에는 없으므로 해당 이벤트는 들어오지 않는다.
  팀 삭제/멤버 제거/유저 삭제에 따른 동기화 기능은 이 환경에서 검증되지 않는다.
- 다른 환경과 공유 인프라(같은 Kafka/Eureka 클러스터)로 연동하려면 그쪽 접속 정보를
  받아 별도로 구성을 변경해야 한다.

## 서버 현황

- 홈서버에는 이미 여러 프로젝트가 `~/Downloads/<프로젝트명>` 구조로 배포되어 있고,
  각각 독립된 Docker 컨테이너로 떠 있다.
- 호스트 포트 3306(mysql), 6379(redis), 8080, 9000-9001(minio) 등이 이미 다른
  컨테이너에서 사용 중이므로, cowork용 컨테이너는 포트를 옮겨서 띄운다
  (`docker-compose.homeserver.yml` 참고).
- nginx는 `/opt/homebrew/etc/nginx/servers/kimtaeeun.conf` 한 파일에 경로별로
  `location` 블록을 추가하는 구조로 운영되고 있다.

## 배포 절차

```bash
ssh snowykte0426@<홈서버 주소>

mkdir -p ~/Downloads/cowork
cd ~/Downloads/cowork
git clone https://github.com/team-cowork/cowork-server.git .

cp .env.example .env
# .env를 열어 아래 값을 채운다 (다른 프로젝트와 겹치지 않는 값 사용):
#   MYSQL_ROOT_PASSWORD, MYSQL_USER, MYSQL_PASSWORD
#   JWT_SECRET, DATAGSM_CLIENT_ID
#   VAULT_HOST=cowork-vault, VAULT_PORT=8200, VAULT_SCHEME=http, VAULT_TOKEN=dev-root-token
#   GITHUB_APP_SERVICE_URL / GITHUB_APP_INTERNAL_API_KEY (연동 안 하면 임의 값)
#   MYSQL_HOST_PORT=3307  # 호스트에 이미 3306을 쓰는 mysql이 있으면 반드시 겹치지 않는 값으로 지정
#   KAFKA_EXTERNAL_HOST_PORT=19094  # kafka EXTERNAL 리스너용 호스트 포트. 이 배포에서는
#                                    # 쓰이지 않지만 base 기본값(9094)이 막혀 있으면 지정 필요
#   COWORK_CONFIG_HOST_PORT=18761   # cowork-config(Eureka) 호스트 포트. base 기본값(8761)이
#                                    # 막혀 있으면 지정 필요
#   COWORK_PROJECT_HOST_PORT=18089  # cowork-project 호스트 포트. base 기본값(8089)이
#                                    # 막혀 있으면 지정 필요 — nginx location의 proxy_pass와
#                                    # 반드시 같은 값을 써야 한다

docker compose \
  -f docker-compose.yml \
  -f docker-compose.override.yml \
  -f docker-compose.homeserver.yml \
  up -d --build cowork-project
```

`cowork-project`가 `depends_on`으로 필요한 `mysql`, `kafka`, `vault`, `vault-init`,
`cowork-config`까지 자동으로 함께 기동된다. 다른 서비스(postgres, redis, minio,
mongodb, elasticsearch, gateway 등)는 project의 의존성이 아니므로 뜨지 않는다.

확인:

```bash
docker compose ps
curl -s http://127.0.0.1:18089/actuator/health
```

## nginx 설정

`/opt/homebrew/etc/nginx/servers/kimtaeeun.conf`의 `server { ... }` 블록 안,
기존 `location` 블록들 사이에 아래를 추가한다:

```nginx
location /cowork/project/ {
    proxy_pass         http://127.0.0.1:18089/;
    proxy_http_version 1.1;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;
}
```

적용 전 반드시 문법 검증 후 reload한다:

```bash
nginx -t
nginx -s reload
```

적용되면 `https://kimtaeeun.site/cowork/project/`로 접근할 수 있다.
(Cloudflare가 플렉시블 모드로 HTTPS를 종단하므로 서버 입장에서는 HTTP 80 포트로만
요청이 들어온다.)

## 재부팅 시 자동 기동

이 홈서버는 재부팅 후 Docker Desktop이 컨테이너를 자동으로 재개하지 않아서,
`~/Library/LaunchAgents/site.kimtaeeun.docker-startup.plist`가 로그인 시
`~/Downloads/docker-startup.sh`를 실행해 앱별 컨테이너를 순서대로 `docker start`
하는 방식으로 운영되고 있다. cowork도 같은 방식으로 등록해뒀다:

```bash
# cowork-project (독립 배포, ~/Downloads/cowork)
# vault는 dev 모드라 재시작하면 저장된 시크릿이 날아가므로 vault-init을 매번 다시 돌린다.
/usr/local/bin/docker start cowork-mysql cowork-vault cowork-kafka
sleep 30
/usr/local/bin/docker start cowork-vault-init
sleep 10
/usr/local/bin/docker start cowork-config
sleep 15
/usr/local/bin/docker start cowork-project
```

새 서비스를 이 홈서버에 추가로 배포한다면 이 스크립트에도 같은 패턴으로 추가해야
재부팅 후에도 살아난다.
