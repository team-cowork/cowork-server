# 운영 배치와 변경 제약

## 관리 경계

GSM SV의 VM 생성·삭제·사양, 공인 포트 전달과 HTTPS 연결은 패널에서 관리한다.
새 API나 Proxmox 권한을 기다리는 작업으로 남기지 않는다. 코드로 관리하는 범위는 기존 VM 내부다.

| 대상 | 관리 원본과 적용 주체 |
|---|---|
| 앱 포트·자원 한도·mount·허용 peer·설정 및 비밀 참조 | Git의 [슬롯 명세](../deploy/prod/slots/) |
| 앱 비밀·SSH 접속 정보·Config 앱 속성 | Vault |
| IaC state 암호화 키·알림 webhook | GitHub Secrets |
| Compose plugin·host 방화벽·모니터링 Compose·VM별 Alloy | [운영 CD](../.github/workflows/cowork-prod-cd.yml)의 Ansible |
| Grafana folder·datasource·dashboard | Terraform |
| 앱 이미지 교체·readiness 확인·컨테이너 복구 | [운영 CD](../.github/workflows/cowork-prod-cd.yml) |

Prometheus 규칙과 Alertmanager 설정도 Git으로 관리한다. Terraform이 관리하는 Grafana 객체를
파일 provisioning이나 UI에서 동시에 관리하지 않는다. 앱 컨테이너는 Terraform state에 넣지 않는다.
Vault policy·인증 role의 초기 신뢰 구성과 복구는 관리자 bootstrap 범위이며 현재 Terraform 대상이 아니다.

앱 VM은 실행 슬롯이다. 동일한 API·이벤트·DB 계약과 슬롯 한도를 지키는 구현은 소스와
`Dockerfile.prod`에서 런타임과 검증 명령을 소유한다. 런타임 변경이 비밀 참조나 포트,
자원 요구량도 바꾸면 슬롯을 함께 수정한다. 컨테이너 한도 변경으로 VM의 CPU·RAM이 늘어나지는 않는다.

앱의 기존 설정 로딩 코드가 슬롯 환경변수와 Config Server 응답을 직접 읽는다.
Config Server에 최초 접속할 주소·프로파일·인증정보는 시작 환경변수로 전달한다.

Stage CI(`develop` push·`main` 이외 대상 PR)는 `Dockerfile.local`을 사용한다.
Prod CI(`main` push·`main` 대상 PR)는 `Dockerfile.prod`를 사용한다. 이미지 빌드와 `verify` 단계,
PR 기준·현재 소스의 커버리지 수집에 같은 파일 구분을 적용한다. 런타임을 교체할 때는 두 파일을 함께 갱신한다.
테스트는 각 모듈의 기본 테스트 명령과 설정으로 실행한다. CI와 커버리지 수집기는 테스트 종류나
클래스·파일 목록으로 실행 대상을 제한하지 않는다.

## 배치와 비밀 값

MySQL·PostgreSQL·MongoDB·Redis·Elasticsearch·Kafka와 SeaweedFS는 `cowork-db` 한 VM에 둔다.
앱 VM마다 DB 엔진을 설치하지 않는다. 앱, Config/Eureka, Vault는 각각 전용 VM을 사용한다.
관리 비용을 줄이기 위한 배치이며 부하 측정으로 최적성을 입증한 구성은 아니다.
DB VM의 디스크와 장애 범위가 집중된다. 업무 데이터의 백업·복원과 RPO/RTO는 검증하지 않았다.

비밀이 아닌 슬롯 값은 Git에서 변경한다. 슬롯의 비밀 참조가 가리키는 값과 Config가 제공하는 앱
속성은 Vault에서 변경한다. VM의 생성된 환경 파일은 수정하지 않는다. 변경 후 영향받는 서비스를
재배포하며 모든 클라이언트의 동적 refresh를 가정하지 않는다.
프로파일·속성 우선순위는 [설정 규칙](../.claude/rules/config.md), Config 계정 교체는
[접근 보호](./config-server-access.md)를 따른다.

운영 CD의 `update-config`에서 `deployment`는 슬롯 대상의 SSH 문서, `application`은 Config 앱 속성,
`slot-secrets`는 `slot-secrets/{target}` 문서를 교체한다. `slot-secrets` 입력은 해당 경로에 선언된
키 전체를 포함하며 `expected_version`으로 동시 변경을 거부한다. 공유 비밀 경로와 Config bootstrap
계정은 대상별 쓰기 권한에 포함하지 않는다. 비밀 갱신 자체가 서비스를 재배포하지는 않는다.

자격 증명 교체는 발급 측과 소비 측의 적용 순서를 맞춘다. DB 사용자, 공유 JWT, S3 계정,
LiveKit 키는 소비 앱만 바꾸어서는 전환되지 않는다. Config bootstrap 비밀은 Config가 읽는 앱 속성에 넣지 않는다.
Docker 접근 권한을 가진 배포 계정은 VM의 root 상당 권한을 가진다.

## 접근과 적용 조건

외부 앱 HTTP는 Gateway로 받는다. backend·Config·DB·S3는 사설 IP 바인딩과 허용 peer 방화벽을
함께 사용한다. VM 주소·포트·peer 변경은 관련 슬롯과 host 정책을 함께 적용한다.
[GitHub App](../deploy/config/github-app/README.md)과 [LiveKit](../deploy/config/livekit/README.md)의
공개 경로는 별도 운영 경계다.

앱·Config는 [공통 네트워크 명세](../deploy/prod/host-network.json)의 MTU 1400 전용 bridge를 사용한다.
Ansible은 외부 경로의 MTU가 이 값 이상인지 확인한다. MTU 변경 시 새 이름의 네트워크를 만들며
기존 네트워크와 Docker daemon은 변경하지 않는다. CD는 새 컨테이너만 전환하고 복구 시 이전 네트워크를 유지한다.

모니터링 관리 화면은 loopback listener와 SSH 터널로 접근한다. 기존 공개 Caddy 프록시는 중지·비활성화되어 있다.

웹 예정 origin은 `https://service.gsmsv.site:22118`이다. 웹 배포와 TLS는 검증하지 않았다.
origin 변경은 Config → Gateway·Channel 적용 후 프런트의 새 `return_origin` 사용을 시작한다.
인증된 WebSocket·SSE와 실제 음성 UDP 통화도 별도 확인이 필요하다.

Team↔Preference와 Project↔Channel은 상대 snapshot을 기다릴 수 있다. 빈 데이터셋에서는 전체 앱
배포를 사용하고 11개 job이 함께 기동할 runner 여유를 확보한다. 순차 readiness 대기는 교착될 수 있다.
앱 CD의 `check_only`는 일부 방화벽 재적용과 임시 Vault 토큰 발급·갱신·폐기를 수행한다.
IaC의 `check_only`는 정적 검증만 수행하며 host 적용·Terraform plan·실제 기동을 검증하지 않는다.

## 실패 복구와 남은 제약

이전 앱 컨테이너를 다시 시작해도 DB migration과 외부 부작용은 되돌아가지 않는다.
부분 적용된 migration은 실제 스키마부터 확인한다. 릴리스의 슬롯·비밀 버전 기록은 복구 판단 자료이며,
참조한 Vault 문서와 Config 앱 속성을 자동으로 되돌리는 기능은 아니다.

Terraform은 로컬 backend를 사용한다. 실행 도구가 state를 암호화하여 운영 VM·DB·Vault 밖의 저장소
`infra-state` 브랜치에 보관하고 Git ref로 동시 실행을 잠근다.
암호화 키와 Vault 복구 자료도 관리 대상 밖에 보관한다. state 저장 실패 시에는 남은 잠금과 암호화된
복구 파일을 확인하고 복구한 뒤 재개한다. 잠금을 삭제하거나 빈 state로 다시 적용하여 우회하지 않는다.
초기화와 잠금 복구 조건은 [IaC 운영 안내](../deploy/iac/README.md)를 따른다.
로컬 변경도 같은 state와 잠금을 사용하며 운영 CD와 동시에 같은 VM을 수정하지 않는다.

Vault 자동 unseal은 구성하지 않았다. 일반 CD는 외부에 보관한 키를 사용하지만 CD 밖에서 Vault가
재시작하면 봉인 복구가 필요하다. 복구 후 Config 조회, 앱 readiness와 Eureka 등록을 확인한다.
앱 CD·설정 변경 인증의 [수명·교체 자동화](./todo/items/42-deployment/vault-auth-automation.md)와
[참조 기반 릴리스 정리](./todo/items/44-deployment/release-retention.md)는 미구현이다.
