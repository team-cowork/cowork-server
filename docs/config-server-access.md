# Config Server 접근 보호 운영

서비스별 bootstrap 계정·운영 HTTPS·허용 peer 네트워크를 준비하고 교체하는 절차다.
허용 endpoint의 기준은 [`ControlPlaneAccessPolicy`](../cowork-config/src/main/kotlin/com/cowork/config/security/policy/ControlPlaneAccessPolicy.kt)다.
운영 인증서는 모든 런타임이 신뢰하는 CA에서 발급하고, 사설 DNS는 인증서 도메인을 Config VM의
사설 IP로 해석한다. 로컬 HTTP는 격리된 개발 네트워크에서 사용한다.

## 로컬 준비

`.env`에 기존 DB·Kafka·Vault bootstrap을 준비한 다음 한 번 실행한다.

```sh
python3 deploy/config/generate-control-plane-credentials.py --profile local --output deploy/local/secrets/config-access
docker compose --env-file .env --env-file deploy/local/secrets/config-access/compose.env up -d
```

생성기는 기존 출력 디렉터리를 덮어쓰지 않는다. 생성 자료는 제한된 권한으로 보관하고 내용을 로그에
출력하지 않는다. Windows에서는 출력 디렉터리의 NTFS ACL을 현재 사용자로 제한한다.

`deploy/local/stack.sh`는 생성한 `compose.env`를 사용한다. 개별 `deploy/local/services/*.sh`는
해당 서비스의 `.env` 파일만 읽는다. IDE에서 직접 실행할 때도 `config.env` 또는 `{서비스}.env`의
bootstrap 값을 환경변수로 주입한다. Prometheus는 전용 초기화 컨테이너가 기록한 자기 계정 파일만
읽으며 서비스들의 비밀번호 파일은 마운트하지 않는다.

## 운영 시크릿 준비

운영 자격 증명은 별도 디렉터리에서 `--profile prod`로 생성한다. 각 `{서비스}.json`을
`secret/control-plane/prod/{서비스}`에 저장하고 `secret/deploy/{target}`의 `runtime_refs`에서
`CONFIG_CLIENT_USERNAME`, `CONFIG_CLIENT_PASSWORD`를 참조한다. `accounts.json` 전체는
`secret/control-plane/prod/server`의 문자열 키 `CONFIG_SERVER_ACCOUNTS_JSON`에 저장하고 Config
배포 문서만 참조한다. 이 경로들은 Config Server가 읽는 애플리케이션 경로에 넣지 않는다.

Config 배포 문서에 다음 `runtime` 또는 `runtime_refs`를 준비한다.

| 키                            | 값                                                                     |
|-------------------------------|------------------------------------------------------------------------|
| `CONFIG_SERVER_ACCOUNTS_JSON` | 생성한 계정 배열 JSON 문자열; 모든 레코드의 `profile`은 `prod`         |
| `CONFIG_TLS_CERTIFICATE`      | 서버 인증서와 중간 인증서 PEM 전체                                     |
| `CONFIG_TLS_PRIVATE_KEY`      | 대응하는 PKCS#8 PEM 개인키                                             |
| `CONFIG_SERVER_URL`           | `https://config.example.com:8761` 형태의 실제 인증서 도메인            |
| `EUREKA_SERVER_URL`           | 같은 서버의 `https://config.example.com:8761/eureka/`                  |
| `CONFIG_ALLOWED_CIDRS`        | 배포 VM과 monitoring VM의 사설 IPv4 CIDR을 쉼표로 연결; 가능하면 `/32` |
| `BIND_IP`, `HOST_PORT`        | Config VM의 사설 IPv4와 공개 포트; 기본 포트 `8761`                    |
| `VAULT_TOKEN`                 | 아래 전용 정책만 가진 유효기간 24시간 이하 토큰                        |

모든 앱과 monitoring 배포 문서에도 HTTPS URL과 자기 계정 두 값을 설정한다. 인증 관련 값을
`application` overrides나 일반 Config 속성으로 배포하지 않는다. 서버 TLS 재료와 bootstrap 계정은
컨테이너 환경을 읽을 수 있는 운영자에게 접근 가능하므로 VM·Docker 관리 권한을 제한한다.

### 기존 공통 Vault 값 이동

Vault composite의 `default-key`를 빈 문자열로 설정했으므로 `secret/application`은 응답에 포함되지
않는다. 최초 전환 전에 기존 서비스 속성을 보존하면서 필요한 값만 해당 서비스 경로에 추가한다.

| 수신 서비스 | 기존 공통 경로에서 옮길 키 |
|-------------|-------------------------|
| channel, project, team, roadmap | `MYSQL_USER`, `MYSQL_PASSWORD` |
| chat | `JWT_SECRET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` |
| team, user | `S3_ACCESS_KEY`, `S3_SECRET_KEY` |
| preference | `preference.db.username`, `preference.db.password`라는 실제 조회 키로 저장 |

Gateway의 `jwt.secret`, authorization의 `JWT_SECRET`·`DB_DSN`, notification의 `db.dsn`, user의
`DB_USERNAME`·`DB_PASSWORD`도 서비스 경로에 존재해야 한다. 환경별 시크릿은
`secret/cowork-{서비스}/prod` 또는 `/local`에 저장하고 환경 공통 기본 경로에는 환경별 시크릿을 두지
않는다. 로컬 seed는 필요한 수신 서비스에만 값을 기록한다. 운영에서 로컬 seed를 실행하지 않는다.

### Vault 최소 권한과 만료

```sh
python3 deploy/config/vault/render-config-policy.py --profile prod > config-server-prod.hcl
vault policy write cowork-config-prod config-server-prod.hcl
vault token create -policy=cowork-config-prod -no-default-policy -ttl=24h -explicit-max-ttl=24h
```

토큰 발급 출력은 비밀 채널에서만 처리한다. 정책은 11개 서비스의 기본·해당 프로파일 KV v2 값에
`read`만 허용하며, 공통 `application`, 다른 프로파일, `deploy`, `control-plane`에는 권한이 없다.
KV mount가 `secret`이 아니면 생성기에 `--backend`를 함께 지정한다.

운영 Config 배포는 Vault HTTPS로 `lookup-self`를 호출해 단일 전용 정책과 남은 TTL 10분~24시간을
검사한다. 정책 본문은 Vault 관리자가 생성한 내용 그대로 등록한다. 토큰은 자동 갱신하지 않으므로
만료 전 새 토큰 발급 → Vault 배포 참조 수정 → Config 재배포를 수행한다. 토큰 만료 알림과 일일
교체 작업을 운영 스케줄에 등록한다. root·default·추가 identity 정책이 붙은 토큰은 배포에서 거부한다.

## 네트워크와 배포 순서

Config VM은 Docker의 iptables 방식을 사용하고 배포 사용자에게 필요한 `sudo -n iptables`와
`iptables-restore` 권한을 제공한다. `CONFIG_ALLOWED_CIDRS`와 실제 배포·monitoring peer를 대조한다. `check_only`는 정적 검증이므로
실제 네트워크 차단을 별도로 확인한다. 클라우드 보안 그룹도 같은 peer 목록으로
제한하고 Docker 재시작 시 규칙이 재적용되도록 호스트 운영 설정을 유지한다.

최초 HTTPS 전환은 서버 포트가 한 개이므로 점검 시간에 진행한다.

1. 서비스별 Vault 값, 새 계정, 인증서, 허용 CIDR, 전용 Vault 토큰을 준비한다.
2. 모든 배포 문서의 bootstrap을 갱신하고 `check_only`를 실행한다.
3. Config Server를 배포하고 실제 DNS·인증서·health를 확인한다.
4. 앱과 monitoring을 새 버전·자기 자격 증명으로 배포하고 Eureka 등록 상태를 확인한다.
5. 허용 peer에서 자기 설정 조회 성공, 다른 서비스·다른 프로파일 거부, 미인증 거부를 수동 확인한다.
   비허용 네트워크에서는 포트 연결 자체가 차단되는지 확인한다. 시크릿 응답은 파일·CI 로그에 남기지 않는다.

이 작업 중 실제 서비스 기동·외부망 차단 검증은 운영 환경에서 수행한다. 저장소 검증은 핵심 권한 정책
단위 테스트, 클라이언트 컴파일, 배포 정적 검사로 제한한다.

## 교체·폐기·복구

비밀번호 교체는 `--suffix=-v2` 같은 새 username으로 발급한다. 새 계정 레코드를 기존 서버 계정 배열에
추가하여 Config를 먼저 배포하고, 대상 서비스의 계정 두 값을 교체하여 재배포한 다음 기존 레코드를
제거한다. 서버의 동일 username 중복은 허용하지 않는다. 변경된 서비스의 레코드만 병합한다.

유출된 계정은 서버 배열에서 즉시 제거하고 Config를 재배포한다. 기존 프로세스의 메모리에 로드된
애플리케이션 시크릿은 계정 폐기로 지워지지 않으므로 필요한 시크릿도 교체한다. 인증서나 토큰 교체도
배포 문서를 바꾼 뒤 Config 재배포로 적용한다.

실패 시 보관된 이전 컨테이너와 해당 버전의 배포 snapshot으로 복구한다. 배포 스크립트는 이전
컨테이너에 기록된 health 프로토콜을 사용한다. 인증 도입 전 버전으로 복구해야 하면 허용 CIDR 방화벽을
유지하고 앱 URL도 이전 프로토콜로 맞춘다. 인증 우회 계정이나 전역 익명 허용을 추가하지 않는다.

인증 실패·권한 거부·등록 identity 거부와 성공한 설정 조회를 감사 로그에 남긴다. 헤더·비밀번호·Vault
응답값을 기록하지 않는다. 거부 로그의 `remote`별 증가와 성공 조회 로그의 `client`별 반복 횟수를
로그 수집기에서 관측하며 비정상 증가 알림 임계값은 실제 배포·refresh 빈도를 기준으로 설정한다.
