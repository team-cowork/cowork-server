# VM 이름과 서비스 배치 정리

- **서비스**: GSM SV VM, Config/Eureka, Gateway, Team, Project, Preference, Voice, Roadmap, Vault, prod-cd
- **우선순위**: 🔴 높음
- **현재 상태**: Vault 전용 VM과 교체 앱 VM을 준비했으며, 업무 데이터를 초기화하고 SeaweedFS로 전환했으며 이전 VM 3개를 삭제했다. 엔진 버전 정렬을 마쳤으며 Datastores의 Compose 전환과 GitHub 경로의 앱 배포는 진행 중이다.
- **관련 작업**: [운영 배포 기준 SHA 복구](../62-deployment/runtime-deployment-baseline-recovery.md), [운영 Config 접근 보호와 AppRole 전환](../65-security/config-control-plane-rollout.md), [운영 Eureka 등록과 광고 주소 복구](../66-deployment/eureka-registration-recovery.md), [공개 API 진입점과 TLS 인증서 정합성 복구](../67-configuration/public-api-tls.md), [모듈별 MySQL·Redis 배치 전환 검토](../58-deployment/module-datastore-placement.md)

## 문제

2026-10-13 SSH 점검에서 운영 Config, Gateway, Team과 Vault는 `cowork-db` VM의 `10.0.0.93`에서 실행되어 있었다.
전용 `cowork-config` VM에는 `local` 프로파일 Config와 별도 데이터 저장소 묶음이 있었고,
전용 `cowork-gateway` VM에는 `local` 프로파일 Gateway가 있었다. 전용 `cowork-team` VM에는 컨테이너가 없었다.
VM 이름만으로 운영 서비스의 실행 위치를 판단할 수 없는 상태다.

같은 날 GSM SV 패널에서 `owork-voice`, `cowork-preference-`, `cowork-app`을 확인했다.
앞의 두 이름에는 오타가 있고, `cowork-app`에서는 Project가 실행되고 있었다.
사용자는 이름 오타와 실제 역할이 다른 배치를 모두 조정하도록 요청했다. Config/Eureka는 전용 Config VM에 배치하는 것을 기준으로 한다.
모니터링 VM과 모니터링 구성은 이번 작업에서 제외한다.

패널의 VM 설정 화면에는 사용 목적, 스냅샷, CPU·메모리 변경 항목이 있었으나 이름 변경 항목은 찾지 못했다.
관측한 생성 화면의 최대 사양은 기존 일부 VM보다 작다. 이름 변경을 위해 먼저 삭제하면 같은 사양으로 복구할 수 없는 경우가 있다.
관리자가 지원하는 이름 변경 방법, 유지할 자원, 이관할 데이터를 확인한 뒤 재생성 대상을 정한다.

## 관측한 배치와 조정 방향

| 패널 VM / 식별자 | 관측한 상태 | 조정 방향 |
|---|---|---|
| `cowork-db` / `gsmgpu1:108` | `10.0.0.93`에서 운영 Config·Gateway·Team·Vault와 데이터 저장소가 함께 실행되어 있었다. | Config·Gateway·Team·Vault를 각각 전용 VM으로 옮기고 데이터 저장소는 유지한다. |
| `cowork-config` / `gsmgpu1:136` | `10.0.0.169`, `2 vCPU / 4 GB / 40 GB`. 기존 `local` Config와 MySQL·PostgreSQL·Redis·Kafka 등 별도 구성이 있었다. | 사용하지 않는 local 데이터 저장소를 제거하고 운영 Config/Eureka 전용 배치로 정리한다. |
| `cowork-gateway` / `gsmgpu1:132` | `10.0.0.127`에서 `local` Gateway가 실행되어 있었다. | 운영 Gateway를 배치하고 공개 전달 규칙과 Eureka 광고 주소를 맞춘다. |
| `cowork-team` / `gsmgpu2:115` | `10.0.0.74`, SSH 점검 당시 컨테이너가 없었다. | Team의 배포 목적지와 광고 주소를 이 VM으로 맞춘다. |
| `cowork-app` / `gsmgpu2:134` | `10.0.0.145`에서 Project가 실행되어 있었다. 사양은 `8 vCPU / 16 GB / 70 GB`였다. | 이름을 `cowork-project`로 정리한다. 기존 VM의 이름 변경 가능 여부와 재생성 시 자원 감소를 먼저 확인한다. |
| `cowork-preference-` / `gsmgpu2:153` | `10.0.0.167`, `4 vCPU / 8 GB / 70 GB`. Preference가 실행되어 있었다. | 이름을 `cowork-preference`로 정리하고 데이터·디스크 요구량에 맞춰 유지 또는 재생성을 결정한다. |
| `owork-voice` / `gsmgpu3:109` | `10.0.0.130`, `2 vCPU / 4 GB / 20 GB`. SSH host key 불일치로 컨테이너 확인은 중단했다. | 이름을 `cowork-voice`로 정리한다. VM identity와 신뢰한 SSH 지문을 먼저 확정한다. |
| `cowork-roadmap` / `gsmgpu2:150` | 패널에 당일 생성, `2 vCPU / 4 GB / 20 GB`, `10.0.0.132`, SSH 포트 `24150`, cloud-init 설정 중으로 표시되었다. | 초기 설정 완료 뒤 새 접속 정보와 배포 대상을 확인한다. 앞선 SSH 결과를 새 VM의 상태로 사용하지 않는다. |

Roadmap은 앞선 접속 자료의 `gsmgpu3:112`, SSH 포트 `27112`와 식별자가 다르다.
Roadmap은 이번 조정에 앞서 패널에 이미 새 VM으로 표시되어 있었다.
Notification과 Chat도 앞선 SSH 점검에서 host key가 일치하지 않았으므로 다음 접속 전에 실제 VM identity를 확인한다.
키 불일치만으로 VM 재생성이 필요하다고 판단하지 않는다.

## 재생성 제약

> 2026-10-13 진행: 사용자는 트래픽 차단 상태에서 새 구성을 허용했으며 전체 데이터 마이그레이션은 요구하지 않았다. Project·Preference·Voice 교체 VM을 생성했다. 세 VM과 앞서 생성된 Roadmap VM은 모두 `2 vCPU / 4 GB / 20 GB`이다. 공식 Docker 저장소로 Engine·Compose·Buildx를 설치하고 target별 SSH 키, `20m × 5` 로그 회전을 적용했다. 새 키 접속과 Docker 권한을 확인했다. 이전 VM 3개는 영구 삭제했다. 애플리케이션 배포는 아직 완료하지 않았다.

| 새 VM | 식별자 | 사설 IP | SSH 포트 | 준비 상태 |
|---|---|---|---|---|
| `cowork-project` | `gsmgpu2:167` | `10.0.0.88` | `24167` | 초기 설정 완료 |
| `cowork-preference` | `gsmgpu2:168` | `10.0.0.89` | `24168` | 초기 설정 완료 |
| `cowork-voice` | `gsmgpu1:104` | `10.0.0.90` | `21104` | 초기 설정 완료 |
| `cowork-roadmap` | `gsmgpu2:150` | `10.0.0.132` | `24150` | 초기 설정 완료 |

기존 Project의 앱 사용량은 약 724 MiB, Preference는 약 378 MiB였다. 두 VM에는 앱 데이터 볼륨이 없었다.
Team과 Gateway도 `2 vCPU / 4 GB`로 줄이는 방향을 정했다. DB VM은 데이터 저장소를 함께 실행하므로 사양을 유지한다.
기존 Vault의 관리자·복구 자격 증명이 없어 별도 볼륨과 로컬 포트 `8201`에 새 Vault를 초기화했다.
기존 운영 Vault와 그 전용 데이터 볼륨·갱신 cron은 제거했다. 새 root token·unseal 자료는 저장소 밖의 제한 권한 파일에 보관했다.
실제 연결 값과 GitHub 자격 증명의 전환은 후속 단계에서 확인한다.

## 추가 진행 상태 (2026-10-13)

| 항목 | 확인한 상태 | 남은 작업 |
|---|---|---|
| Config VM | 기존 local 컨테이너 13개의 자동 재시작을 해제하고 정지했다. 이전 local 컨테이너 13개와 전용 볼륨 18개를 제거했다. | GitHub 경로로 prod Config를 배포한다. |
| Gateway VM | 기존 local Gateway 컨테이너를 제거했다. | prod Gateway 배포와 공개 TLS 연결을 확인한다. |
| Team VM | 패널 변경 후 재기동했다. SSH에서 2 vCPU와 `lsmem`의 online 메모리 4 GiB를 확인했다. `virtio_balloon` 장치가 있으며 현재 `MemTotal`은 약 2.16 GiB였다. | Team 기동 후 실제 메모리와 readiness를 확인한다. |
| Vault HTTPS | DB VM의 HTTPS 슬롯 2개는 `chcowork.https.gsmsv.site:80`, `livekit.https.gsmsv.site:7880`이 사용하고 있었다. 누락된 Vault 도메인을 새 VM의 `8200`에 연결했다. 공개 health의 TLS 검증, HTTP 200과 새 cluster ID 일치를 확인했다. | Config AppRole과 GitHub 토큰의 실제 인증을 확인한다. |
| Vault VM | `cowork-vault`, `gsmgpu1:105`, `10.0.0.91`, SSH `21105`를 생성했다. 사양은 `1 vCPU / 2 GB / 20 GB`이다. Docker 초기 설정과 신규 Vault 볼륨 이관을 마쳤고 기존 복구 키와 cluster ID를 유지했다. | 후보 컨테이너를 정지하고 GitHub 복구 경로로 최종 컨테이너를 기동한다. |
| AppRole 송신 경로 | Config VM의 공개 HTTPS health 요청 3회가 모두 성공했다. 새 Vault의 TCP peer 표본 20개에서 프록시 `10.0.0.117`만 관측했다. | 관측한 `10.0.0.117/32`에서 AppRole 로그인이 허용되는지 확인한다. 이 CIDR은 Config VM 자체가 아니라 공용 TLS 프록시를 식별한다. |

### 최신 적용 상태

- Config·Gateway·DB와 기존 앱 VM에서 이전 앱·Vault·임시 빌드 컨테이너 28개와 전용 볼륨 25개를 제거했다. User의 무회전 로그 약 27.2 GB도 컨테이너와 함께 제거됐다. Docker와 모니터링 컨테이너는 재시작하지 않았다.
- DB VM의 MySQL·PostgreSQL·MongoDB·Redis·Elasticsearch·Kafka 및 이전 MinIO 공개 포트 전달 규칙 10개를 삭제했다. 패널에서 제거된 것을 확인했고, 운영 Mac에서 해당 TCP 포트 10개에 연결되지 않았다.
- LiveKit을 저장소의 고정 `v1.13.7` 이미지로 교체하고 `http://10.0.0.90:8089/voice/webhook`을 등록했다. 공개 TLS와 미인증 WebSocket 요청의 LiveKit 도달을 확인했다. RTC 공인 주소는 `158.247.251.109`, 광고 TCP·UDP 포트는 각각 `32154`·`37932`로 실제 매핑과 맞췄다. 외부 TCP 연결은 성공했다. UDP 왕복과 실제 음성 통신은 아직 확인하지 않았다. 별도 relay 포트 범위가 없는 현재 GSM SV 연결에서는 TURN을 비활성화했다.

- GSM SV 패널에서 이전 `cowork-app`(`gsmgpu2:134`), `cowork-preference-`(`gsmgpu2:153`), `owork-voice`(`gsmgpu3:109`)를 영구 삭제했다. 새 Project·Preference·Voice VM은 목록에 남아 있다.
- 초기화 직후 MySQL 업무 테이블, Preference schema 객체, Chat·Voice collection, Redis DB0 key, Chat 검색 색인은 모두 0개였다. Kafka는 4.3.1의 새 영속 볼륨에서 실행되며 52개 topic의 end offset과 consumer group은 모두 0이었다.
- MySQL `9.7.2`, PostgreSQL `18.6`, MongoDB `8.3.11`, Redis `8.10.1`, Elasticsearch `9.5.4`로 엔진을 정렬했다. 이전 엔진 컨테이너와 전용 볼륨을 제거했다. Elasticsearch의 새 내부 운영 색인에 문서가 생겼으므로 전체 색인이 0개라는 상태를 유지한다고 표현하지 않는다. 업무 데이터와는 구분한다.
- DB의 포트별 방화벽을 설치했다. 허용 서비스의 DB 연결은 성공하고 비허용 Vault VM의 보호 포트 10개 연결은 차단되었다. 기존 MySQL·PostgreSQL·MongoDB exporter의 인증 오류는 남아 있으며 제외 범위인 모니터링 설정은 변경하지 않았다.
- Datastores GitHub target, 전용 UID/GID `10002` 계정, Vault 문서와 GitHub Environment를 준비했다. 일반 CD는 기존 외부 볼륨 7개와 cluster identity를 검증한다. 최초 Compose 소유권 전환과 main GitHub 실행 결과는 아직 확인하지 않았다.
- Storage GitHub target과 전용 Vault 문서·GitHub Environment를 준비했다. 일반 CD는 기존 데이터를 유지하며, 최초 초기화와 이전 엔진 제거는 수행하지 않는다. 이 GitHub 경로의 실제 실행은 아직 확인하지 않았다.

- Vault는 새 전용 VM에서 `f6fdc56e`의 정상 `redeploy`를 통과했다. GitHub runtime 성공 기록과 공개 TLS health를 확인했다.
- Config 전용 AppRole의 로그인과 허용 경로 조회는 성공했고 배포·제어 경로와 `local` 조회는 거부되었다. 실행 중인 새 Config의 인증 갱신은 아직 확인하지 않았다.
- GitHub의 `update-config`로 배포 문서 5개와 Team·User·Chat 애플리케이션 문서 3개를 갱신했다. 실제 Vault 값 일치와 일회성 입력 secret 삭제를 확인했다.
- Channel은 `8 vCPU / 20 GB`에서 `4 vCPU / 8 GB`로 줄였다. 패널과 게스트의 온라인 CPU·메모리 용량을 확인했다. 기존 앱은 자동 재시작을 해제하고 정지했으며 새 CD 배포를 기다린다.
- Gateway는 hotplug 제약으로 기존 `4 vCPU / 8 GB`를 유지했다. 공개 API 도메인의 전달 대상은 새 VM의 `8080`으로 바꿨다.
- 웹 원본은 배포 예정인 `https://service.gsmsv.site:22118`로 설정했다. 웹은 아직 배포되지 않았으며 이 주소의 TLS 응답은 확인하지 않았다.
- 사용자는 기존 업무 데이터 보존을 요구하지 않으며, 초기 상태와 같은 구성이 더 명확하면 파괴적 재구성을 허용했다. 기존 MinIO 유지 판단을 철회하고 빈 SeaweedFS 4.47 데이터셋으로 전환했다. MinIO 컨테이너 2개와 전용 볼륨을 제거했다.
- MySQL 업무 DB 7개, PostgreSQL Preference, MongoDB Chat·Voice, Redis DB0, Chat 검색 색인과 Kafka 데이터셋을 초기화했다. 새 Vault와 현재 유효한 외부 연동 자격 증명은 유지한다.
- 기존 Voice DB에서 처리 실패 기록 51,325개를 확인했다. Kafka의 실제 로그 경로는 `/tmp/kafka-logs`이며 연결한 `/var/lib/kafka/data` 볼륨을 사용하지 않았다. 새 브로커는 저장소의 고정 이미지와 명시한 영속 경로를 사용한다.
- 초기화 전에 독립 GitHub App을 포함한 업무 컨테이너 17개를 정지하고 자동 재시작을 해제했다. 처음 실행 중이던 컨테이너는 11개였다. 이 기록을 현재 남아 있는 컨테이너 수로 사용하지 않는다. 이후 이전 업무 컨테이너와 VM을 제거했으며 구버전 복구 대상으로 유지하지 않는다.
- GitHub App의 최신 main 이미지와 webhook secret 입력을 준비했다. Config·Project의 잘못된 내부 URL을 GitHub `update-config`로 `http://10.0.0.150:3000`으로 수정했고 내부 API 키 일치를 확인했다. App의 현재 webhook은 비활성이며 URL과 구독 목록이 비어 있다. `issues`·`pull_request` 권한은 있고 `push`에 필요한 Contents 읽기 권한은 없다. 실제 이벤트 수신은 아직 확인하지 않았다.
- 초기화 완료와 서비스 복구는 별도로 판정한다. 앱 readiness·Config 권한·Eureka·공개 API가 확인되기 전에는 운영 복구 완료로 기록하지 않는다.

DB VM의 기존 Config·Gateway·Team 컨테이너는 제거했다. 기존 운영 Vault와 갱신 cron은 폐기했다. DB VM의 중지된 개발용 Vault와 이관용 복제 볼륨도 제거했다.
신규 VM 생성과 기존 컨테이너 정지를 서비스 이관 완료로 판단하지 않는다.

| 항목 | 패널에서 확인한 내용 | 적용 조건 |
|---|---|---|
| OS | 생성 선택지는 `Ubuntu 22.04 LTS`였다. 상세 화면은 `Ubuntu (Cloud-Init)`으로 표시한다. | 기존 OS가 잘못되었다고 단정하지 않고 필요한 Docker·Compose·배포 도구의 지원을 확인한다. |
| 새 VM 사양 | `Basic: 1 vCPU / 2 GB / 20 GB`, `Standard: 2 vCPU / 4 GB / 20 GB`, `Custom: 최대 4 vCPU / 16 GB / 40 GB`였다. | Project의 8 vCPU와 기존 70 GB 디스크는 관측한 생성 화면에서 그대로 재현할 수 없다. |
| 기존 자원 변경 | 설정 화면에서 CPU·메모리 hotplug를 제공한다. | 자원 조정만 필요한 VM은 기존 VM에서 변경 가능한지 확인한다. |
| 스냅샷 | 확인한 Config·Voice 설정에는 생성된 스냅샷이 없었다. | VM 삭제 후 스냅샷이 남는지는 확인하지 않았다. 삭제할 VM 밖에 복구 가능한 자료를 확보한다. |
| 접속 정보 | VM 재생성에 따라 VMID·노드·IP·SSH 포트·호스트 키가 바뀔 수 있다. | 기존 접속 파일이나 Vault 문서를 그대로 사용하지 않는다. |

## GitHub와 Vault의 관리 범위

[`inventory.json`](../../../../deploy/prod/inventory.json)은 서비스와 배포 target을 연결한다.
현재 코드는 target의 VM을 생성하거나 VM 이름에서 실행 위치를 추론하지 않는다.
실제 목적지는 Vault `secret/deploy/{target}`의 `ssh`와 `runtime`·`runtime_refs`로 정한다.
따라서 현재 Config가 DB VM에 있는 배치는 VM 이름을 강제하지 않는 구조에서 가능하지만, 이번에 정한 전용 VM 배치와는 다르다.

| 관리 대상 | 현재 경로와 역할 |
|---|---|
| 배포 소스·순서·상태 | [`cowork-prod-cd.yml`](../../../../.github/workflows/cowork-prod-cd.yml), [`deploy-target`](../../../../.github/actions/deploy-target/action.yml), `deploy/prod`에서 SHA 기반 배포와 runtime 성공 기록을 관리한다. |
| 배포 주소와 자격 증명 | Vault `secret/deploy/{target}`에 보관하며 GitHub의 `update-config` 경로로 갱신한다. 비밀값 자체는 Git에 넣지 않는다. |
| GitHub 연결 | `Prod-CD(<target>)`, `Config-Update(<target>)`의 변수·secret·`main` 정책이 Vault 접근과 실행 권한을 제공한다. |
| Vault 컨테이너 | [`services/vault.sh`](../../../../deploy/prod/services/vault.sh)와 [`vault/compose.yaml`](../../../../deploy/prod/vault/compose.yaml)로 배포한다. Vault도 GitHub 배포 target이다. |
| Vault 장애 복구 | `Prod-CD(vault)`의 `VAULT_BOOTSTRAP_JSON`과 `vault_recovery=true`를 사용한다. 복구 입력과 unseal 자료는 Vault 밖에도 보관한다. |
| VM 생명주기 | 현재는 GSM SV 패널에서 생성·자원·포트 전달을 관리한다. 이 단계까지 GitHub 자동화가 구현되어 있다고 판단하지 않는다. |

VM 조정 뒤에는 수동 컨테이너 실행이나 VM 환경 파일만 바꾼 상태를 최종 구성으로 남기지 않는다.
Vault 배포 문서와 GitHub 배포 경로에서 같은 상태를 다시 구성할 수 있도록 정리한다.
DB·Redis의 모듈별 분리 여부는 관련 배치 작업에서 결정하며, 이번 VM 이름 정리만으로 데이터 저장소 이관을 확정하지 않는다.

## 할 일

### VM별 이관 입력 확정

- 패널의 VMID·노드·이름·사양·사설 IP·SSH 포트를 최신 목록으로 고정하고 실제 프로세스와 연결한다.
- 변경 대상의 Docker volume·bind mount, DB, 객체 저장소, Kafka, 로컬 파일과 외부 참조를 조사한다.
- Config VM의 기존 데이터 저장소를 사용하는 클라이언트가 있는지 확인하고, 보존·이관·폐기할 자료를 구분한다.
- Project·Preference·Voice의 이름 변경 지원 여부를 확인한다. 재생성이 필요하면 현재 생성 제한 안에서 필요한 자원과 저장 용량을 확정한다.
- 현재 배포 소스·설정·유효한 자격 증명과 새 Vault 복구 자료를 삭제할 VM 밖에 확보한다. 초기화 대상 업무 데이터와 이전 이미지는 이관하지 않는다.
- 새 VM을 먼저 준비할 수 있는지 확인하고, 먼저 삭제해야 하는 경우에는 서비스 중단 범위와 복구 가능한 상태를 확정한다.

### 초기 설정과 배포 값 갱신

- 새 VM의 cloud-init 완료 뒤 배포 계정, Docker, Compose, Bash, curl, flock, Git, Python 3와 필요한 권한을 준비한다.
- 실제 SSH 서버의 신뢰한 host fingerprint와 배포 공개키를 확인한다. 지문 검증을 끄거나 기존 지문을 근거 없이 덮어쓰지 않는다.
- Vault 배포 문서의 `ssh.host`, `ssh.port`, `ssh.user`, `ssh.key`, `ssh.fingerprint`를 갱신한다.
- `ADVERTISE_IP`, `BIND_IP`, `HOST_PORT`, `CONFIG_SERVER_URL`, `EUREKA_SERVER_URL`, `CONFIG_ALLOWED_CIDRS`와 변경된 데이터 저장소 주소를 갱신한다.
- 서비스별 `CONFIG_CLIENT_USERNAME`·`CONFIG_CLIENT_PASSWORD`, Config AppRole의 송신 CIDR과 참조 경로를 새 배치와 대조한다.
- `Prod-CD(<target>)`와 필요한 `Config-Update(<target>)`의 `VAULT_ADDR` 및 접근 권한을 확인한다. 기존 target의 환경을 재사용할 수 있는지 확인한다.
- Vault를 옮기는 경우 데이터 volume, TLS 전달, `VAULT_BIND_IP`, 복구용 `VAULT_BOOTSTRAP_JSON`, 외부 unseal 자료를 함께 준비한다.
- 공개 API와 필요한 서비스의 포트 전달·DNS·인증서를 새 대상에 맞춘다. 주소 변경에 영향을 받는 앱 설정도 함께 갱신한다.
- 새 접속 정보를 접근이 제한된 운영 자료에 반영한다. 비밀번호·개인키·토큰은 이 TODO와 저장소에 기록하지 않는다.

### GitHub 경로로 적용

- 확정한 VM에 `check_only`를 수행한 뒤 Vault, Datastores, Storage, Config/Eureka, 앱 순서로 GitHub 수동 배포를 실행한다.
- Config·Gateway·Team이 각각 전용 VM에서 실행되는지 확인한다.
- 새 배치의 readiness, Config 조회 권한, Eureka identity와 광고 주소, 필요한 projection 상태를 확인한다.
- 이전 업무 컨테이너와 불필요한 등록을 제거한다. 교체 VM이 준비되고 현재 설정·자격 증명이 이전 VM에 의존하지 않는지 확인한 뒤 이전 VM을 삭제한다.
- 대상별 실제 SHA와 runtime 성공 기록을 대조하고 후속 자동 배포가 새 VM을 사용하는지 확인한다.

## 검증

- 패널 목록, 신뢰한 SSH 접속 결과, 컨테이너 배치, Vault의 대상 정보, GitHub 배포 결과를 정적으로 대조한다.
- 전용 VM에서 의도한 서비스와 프로파일이 실행되고 이전 실행 위치에 중복 프로세스가 남지 않는지 수동 확인한다.
- 새 SSH 접속·사설망 연결·공개 HTTPS 연결의 대상과 실제 VM이 일치하는지 확인한다.
- 재생성 전 확보한 자료로 복구 가능한지 확인하고, 이전 주소·VMID·SSH 포트 참조가 남아 있는지 점검한다.
- 운영 수동 확인으로 검증한다. 회귀·통합 테스트는 작성하거나 실행하지 않는다.

## 완료 조건

- VM 이름과 실제 서비스 역할이 일치하며 Config·Gateway·Team이 각각 전용 VM에서 실행되어 있다.
- 조정한 VM의 사양과 초기화 범위가 확정한 요구를 충족하며 이전 데이터셋과 구버전 업무 프로세스가 남아 있지 않다.
- 실제 접속 정보와 SSH 지문, Vault 배포 문서, GitHub Environment, 포트 전달 규칙이 일치되어 있다.
- 앱과 Vault는 GitHub 배포 경로로 관리되며 VM의 수동 설정만을 원본으로 사용하지 않는다.
- 이전 VM과 주소를 참조하는 불필요한 실행·등록·배포 값이 정리되어 있다.
- 모니터링 VM과 구성은 이번 조정에 포함되어 있지 않다.
