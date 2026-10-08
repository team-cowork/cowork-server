# CD health 대기와 SSH 명령 제한 분리

- **서비스**: GitHub Actions, cowork-channel, cowork-chat, 배포 스크립트
- **우선순위**: 🔴 높음
- **현재 상태**: projection 재생이 긴 서비스는 health 대기 중 SSH 세션이 20분에 끊겨 성공·롤백·기록 없이 실패한다
- **관련 작업**: [운영 서비스의 CD 배포 일원화](../52-deployment/prod-cd-unification.md), [user projection 재생 처리량 개선](../56-performance/user-projection-replay-throughput.md)

> **2026-10-08 진척:** `28ebfd4a`에서 `.github/actions/deploy-target/action.yml`의 `command_timeout`을 20분에서 45분으로 늘려 선택지 중 제한 정렬을 택했다. 같은 커밋은 배포 전부터 비정상이던 이전 컨테이너의 롤백 readiness 대기를 생략하고, 실패한 후보 로그를 VM 상태 디렉터리에 보존한다. `deploy/prod/vault-settings.py`의 `validate_deployment`는 `HEALTH_TIMEOUT_SECONDS`를 1~1050의 정수로 제한한다. 상한은 후보 대기와 롤백 복구 대기(각 `HEALTH_TIMEOUT_SECONDS`)에 락 대기·컨테이너 중지·소스 fetch·이미지 pull 여유 600초를 더해 45분 안에 끝나는 값이다. 이 검증은 Vault 갱신, snapshot 읽기(참조 해석 후 포함), VM의 `apply-settings.py`, `deploy/validate.py`에 함께 적용된다. 이미지 pull에는 시간 제한이 없어 여유 600초를 넘는 pull은 막지 않는다. `deploy/channel`·`deploy/chat`의 실제 값 확인, 2026-10-02 chat 배포가 VM에 남긴 후보 컨테이너 확인, 운영 배포 검증은 아직 하지 않았다. 실제 값이 1050을 넘으면 해당 대상의 배포는 snapshot 읽기 단계에서 거부된다.

## 문제

`.github/actions/deploy-target/action.yml`은 `appleboy/ssh-action`을 `command_timeout: 20m`으로 실행한다. VM 안의 `deploy.sh`는 `deploy_container_safely`(`deploy/prod/lib/container.sh`)로 후보 컨테이너를 시작한 뒤 `HEALTH_TIMEOUT_SECONDS`(기본 420, `deploy/prod/lib/environment.sh`) 동안 health를 기다린다. 실패하면 이전 컨테이너로 롤백한다.

channel·chat의 health 경로는 Kafka projection readiness다. 오래 중단되었던 서비스는 밀린 이벤트를 따라잡기 전까지 readiness를 열지 않는다. 2026-10-02 chat 배포(run 36957747683)는 이미지 수신 후 19분 42초 동안 끝나지 않았고, `Run Command Timeout`으로 종료되었다. 기본값 420초였다면 롤백되었을 시간이므로, chat 배포 문서의 `HEALTH_TIMEOUT_SECONDS`는 20분 제한에 근접하거나 초과한 값으로 추정한다. 실제 값은 마스킹되어 아직 확인하지 않았다. channel의 반복 실패도 같은 패턴이다.

SSH 세션이 먼저 끊기면 `deploy.sh`는 롤백도 성공 기록(`deployment_state.py`)도 하지 못한다. 후보 컨테이너가 VM에 남은 채 워크플로는 실패로 끝난다. 다음 배포의 변경 감지(`deploy/prod/build-matrix.py`)는 마지막 성공 기록을 기준으로 하므로 같은 대상이 계속 재배포 대상이 된다.

## 선택지

| 방식 | 내용 | 고려 사항 |
|---|---|---|
| 제한 정렬 | `HEALTH_TIMEOUT_SECONDS` 상한을 `command_timeout`보다 충분히 짧게 검증 | 긴 재생은 항상 롤백되어 배포가 진행되지 않음 |
| 분리 실행 | VM에서 배포를 백그라운드로 실행하고 Actions는 상태 파일을 폴링 | 세션 단절과 무관하게 롤백·기록 보장 |
| 준비 상태 구분 | 배포 health는 liveness와 설정 로드만 보고 projection 따라잡기는 Eureka 등록으로 처리 | readiness 의미를 서비스별로 재정의해야 함 |

## 할 일

- `deploy/channel`, `deploy/chat` 문서의 `HEALTH_TIMEOUT_SECONDS` 실제 값을 확인한다.
- `vault-settings.py`의 배포 문서 검증에 `HEALTH_TIMEOUT_SECONDS`와 `command_timeout`의 관계를 강제한다.
- 선택지 중 하나를 정해 SSH 단절 시에도 롤백 또는 성공 기록이 남도록 배포 흐름을 바꾼다.
- 2026-10-02 chat 배포가 VM에 남긴 후보 컨테이너 상태를 확인한다.

## 검증

- projection 재생이 health 제한을 넘는 상황에서 워크플로가 롤백 또는 명시적 실패 기록으로 끝나는지 확인한다.
- SSH 세션이 끊긴 뒤에도 VM의 배포 결과가 다음 실행에서 판별되는지 확인한다.

## 완료 조건

- 배포 워크플로가 `Run Command Timeout`으로 끝나 VM 상태가 미정으로 남는 경로가 없다.
- `HEALTH_TIMEOUT_SECONDS`가 SSH 명령 제한을 넘는 배포 문서는 검증 단계에서 거부된다.
