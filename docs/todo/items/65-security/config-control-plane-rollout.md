# 운영 Config 접근 보호와 AppRole 전환

- **서비스**: Config, Vault, Gateway, 업무 서비스
- **우선순위**: 🔴 높음
- **현재 상태**: 전용 Config VM의 `main` 배포와 11개 서비스 계정의 실제 설정 조회·접근 제한을 확인했다. 앱 기동의 중복 import 오류 수정과 실행 중 Config의 장시간 인증 수명 관측이 남아 있다.
- **관련 작업**: [VM 이름과 서비스 배치 정리](../68-deployment/vm-placement-rebuild.md)

> **2026-10-13 운영 적용:** `90f17043`의 [Config 배포](https://github.com/team-cowork/cowork-server/actions/runs/38397872030)가 성공했다. 11개 앱 VM에서 자기 계정의 `prod` 조회와 인증된 Eureka 조회는 `200`이었다. 필수 값 누락과 미해결 placeholder는 없었다. 미인증 조회는 `401`, 다른 서비스·`local`·배포 설정 조회는 `403`이었다. 응답 본문과 비밀값은 기록하지 않았다. 배포 검증기의 갱신 성공을 실행 중 Config의 자동 갱신·24시간 이후 재로그인 결과로 해석하지 않는다.

> **앱 기동 오류:** 다섯 Spring 앱의 외부 `SPRING_CONFIG_IMPORT` 주입 때문에 초기 로딩 중 익명 `/application/default` 요청이 발생했다. Config는 이 요청을 `401`로 거부했다. 외부 import 주입을 제거하고 앱의 `application.yml`에 있는 이름·계정·import를 함께 읽도록 수정했다. 접근 정책은 넓히지 않았다. 이 수정의 운영 적용은 남아 있다.

> **2026-10-13 진척:** 배포 코드는 설정 검증과 이미지 확보, 후보 컨테이너 생성 후 Config 방화벽을 변경하도록 수정하였다. 실패 시 기존 `COWORK_CONFIG` 체인과 이번 배포에서 추가한 연결 규칙만 복원한다. 최초 접근 보호 전환에서는 새 사설 CIDR 제한을 유지한다. 방화벽 복구에 실패하면 이전 컨테이너를 재기동하지 않고 실패를 유지한다. 후보의 초기 기동 로그도 VM의 접근 제한 파일에 최대 `20 MiB`로 보존한다. `bash -n`과 `git diff --check`를 통과하였다. 운영 적용과 실제 복구 결과는 아직 검증하지 않았다.

> **추가 관측:** 전용 Config VM에는 방화벽 영속 복원 서비스와 기존 `COWORK_CONFIG` 규칙이 없었다. 재부팅 후 제한이 사라지는 문제를 막도록 root 소유 정책과 Docker 선행 복원 서비스를 구현했다. 코드의 정적 확인과 전용 VM 설치를 마쳤다. 당시 main 배포의 GHCR 인증 누락은 수정되어 위 Config 배포가 성공했다. 설치된 helper는 root 소유 정책만 복원하며 릴리스 캐시의 코드를 sudo로 실행하지 않는다.

> **2026-10-13 진척:** 이전 Vault 클러스터·전용 볼륨, DB VM의 갱신 cron·스크립트·토큰 파일과 중지된 이전 Config를 제거했다. 새 클러스터와 복구 자료는 유지했다. 새 Config의 실제 기동 후 AppRole 갱신·재로그인 확인은 남아 있다.

> **현재 인증 관측:** 새 Config AppRole 로그인과 허용된 `prod` 설정 조회는 성공했다. 배포·제어 경로와 `local` 조회 거부를 확인했다. 실행 중인 Config의 갱신과 재로그인 결과로 해석하지 않는다.

## 문제

2026-10-13 운영 점검에서 Config는 `cowork-db` VM의 `231bfea6` 이미지로 실행되어 있었다.
사설망에서 인증 없이 요청한 `/cowork-user/prod`는 HTTP `200`을 반환했다.
실행 환경에는 정적 `VAULT_TOKEN`이 있었으며 AppRole 자격 증명은 없었다.
공개망에서 같은 설정 응답에 접근할 수 있는지는 확인하지 않았다. 응답 본문과 비밀값은 기록하지 않는다.

현재 [Config 접근 보호 운영 문서](../../../config-server-access.md)와
[`application.yml`](../../../../cowork-config/src/main/resources/application.yml)은
운영 Config에 서비스별 Basic 인증, 사설 IP 바인딩, 허용 CIDR, Vault AppRole을 적용하도록 구성되어 있다.
코드 반영과 운영 적용 사이에 차이가 있다. 이전 토큰부터 폐기하면 현재 설정 조회가 중단될 수 있다.

점검 당시 배포 코드에는 별도의 실패 복구 위험도 있었다.
[`config.sh`](../../../../deploy/prod/services/config.sh)는 Config 방화벽을 적용하고,
[`deploy.sh`](../../../../deploy/prod/deploy.sh)는 그 뒤에 이미지를 가져온다.
[`config-firewall.sh`](../../../../deploy/prod/lib/config-firewall.sh)는 기존 규칙을 보관하지 않으며,
[`container.sh`](../../../../deploy/prod/lib/container.sh)의 복구 처리는 방화벽을 복원하지 않는다.
따라서 뒤 단계가 실패해도 변경된 허용 목록이 남을 수 있다. 이 경로로 운영 장애가 발생했는지는 확인하지 않았다.

## 적용 기준

| 대상 | 적용 기준 |
|------|-----------|
| Config 실행 위치 | 전용 Config VM과 실제 사설 주소를 확정한 뒤 배포 문서에 반영한다. |
| Config·Eureka 주소 | `CONFIG_SERVER_URL`과 `EUREKA_SERVER_URL`에 같은 Config VM의 사설 IPv4 HTTP 주소를 사용한다. |
| 접근 계정 | `CONFIG_SERVER_ACCOUNTS_JSON`과 서비스별 `CONFIG_CLIENT_USERNAME`·`CONFIG_CLIENT_PASSWORD`를 준비한다. |
| Vault 인증 | `VAULT_ROLE_ID`·`VAULT_SECRET_ID`를 Config 전용 bootstrap 경로에서 참조한다. |
| 네트워크 | `BIND_IP`와 `CONFIG_ALLOWED_CIDRS`를 실제 VM 배치 및 송신 주소와 대조한다. |
| 설정 원본 | Vault의 `secret/deploy/{target}`와 서비스별 설정을 GitHub의 `update-config` 경로로 관리한다. |
| 기존 토큰 폐기 | 새 클러스터의 Config 전환과 복구 자료를 확인한 뒤 관련 작업에서 수행한다. AppRole 갱신·재로그인은 별도로 검증한다. |

## 할 일

### 전환 준비

- Config 전용 VM의 배치, 사설 주소, SSH host fingerprint, 배포 계정, 허용 peer를 확정한다.
- 서비스별 Config 계정과 Vault 애플리케이션 경로를 준비하고 기존 설정값의 누락 여부를 대조한다.
- Config 전용 AppRole 정책, 발급 조건, 실제 송신 CIDR을 운영 문서의 기준과 대조한다.
- 각 target의 Vault 배포 문서와 GitHub Environment 참조를 갱신한다. 비밀값을 저장소에 기록하지 않는다.
- 새 이미지와 설정 snapshot, 현재 유효한 인증 수단을 준비한다. 이번 초기화에서 폐기한 미인증 구버전은 복구 대상으로 사용하지 않는다.

### 배포와 실패 복구

- 이미지 확보와 필수 설정 검증을 방화벽 변경보다 먼저 완료하도록 배포 순서를 조정한다.
- 방화벽 변경 전 상태를 보관하고, 배포 실패 시 서비스 복구와 일치하는 규칙으로 복원하는 절차를 구현한다.
- 최초 인증 전환에서 기존 미인증 서버로 복구할 때도 필요한 사설망 접근 제한이 유지되도록 복구 기준을 정한다.
- `check_only`를 실행한 뒤 Config와 앱을 운영 문서의 전환 순서에 따라 배포한다.
- 컨테이너 직접 수정 대신 Vault 설정과 GitHub 배포 경로에 최종값을 반영한다.
- 실행 중인 Config의 AppRole 로그인·갱신과 24시간 상한 이후 재로그인 결과를 기록한다.

## 검증

- 저장소의 설정 키, 배포 순서, 방화벽 복구 분기를 정적으로 대조한다.
- 운영에서 허용 계정의 자기 설정 조회 성공, 미인증 요청 거부, 다른 서비스·다른 프로파일 조회 거부를 수동 확인한다.
- 비허용 네트워크에서 Config 포트 연결이 차단되는지 수동 확인한다.
- `/actuator/health`와 별도로 실제 Vault 설정 조회와 실행 중인 Config의 인증 수명 관리를 확인한다.
- 응답 본문, 비밀번호, Role ID, Secret ID, 토큰을 문서와 CI 출력에 남기지 않는다.
- 회귀·통합 테스트를 작성하거나 실행하지 않는다.

## 완료 조건

- 운영 Config의 실행 버전과 접근 정책이 저장소 문서와 일치되어 있다.
- 설정 조회는 허용된 서비스 계정과 네트워크로 제한되어 있다.
- 운영 Config는 정적 `VAULT_TOKEN`에 의존하지 않고 AppRole로 인증되어 있다.
- 배포 실패 시 컨테이너와 방화벽의 복구 상태가 일치되어 있다.
- AppRole의 실제 갱신·재로그인 결과가 비밀값 없이 기록되어 있다.
- 기존 토큰 폐기는 새 클러스터의 실제 전환 확인과 복구 절차에 연결되어 있다.
