# 운영 배포 기준 SHA 복구

- **서비스**: Vault, Config, Gateway, Authorization, User, Team, Channel, Project, Preference, Roadmap, Voice, Notification, Chat
- **우선순위**: 🔴 높음
- **현재 상태**: Team만 현재 `main` 이력에 속하는 runtime 성공 기준을 갖고 있으며, 나머지 대상의 실제 배포와 기준 복구가 남아 있다.
- **관련 작업**: [Production 배포 결과 표시 수정](../63-deployment/production-deployment-status.md), [User 배포 readiness 실패 원인 확인](../64-reliability/user-deployment-readiness-failure.md)

## 문제

2026-10-13 점검에서 최신 `main`은 `64272167`, 릴리스는 `v20261012.0`이었다. [자동 실행 기록](https://github.com/team-cowork/cowork-server/actions/runs/38236164069)에서는 앱 이미지 12개와 릴리스를 생성했으나, 배포 대상 계산 중 Team의 Git 이력 불일치로 중단되었다. 서버 배포 작업은 모두 생략되었다. 이미지와 릴리스가 존재한다는 사실은 운영 적용 완료를 뜻하지 않는다.

이후 Team을 `ee68258a`로 수동 배포하여 readiness와 runtime 성공 기록을 확인하였다. 나머지 앱 10개의 성공 기준 `231bfea6`과 Vault의 성공 기준 `10280762`는 현재 `main`의 조상이 아니다. User에는 `cowork-runtime` 성공 기록이 없다. 현재 기준으로 자동 실행을 다시 시작하면 다른 대상의 이력 불일치에서 중단될 수 있다.

[배포 문서](../../../../docs/deployment.md)와 [PR #481](https://github.com/team-cowork/cowork-server/pull/481)은 이력 재작성 후 각 대상에 현재 `main` 이력의 SHA를 수동 배포하도록 정한다. 실제 적용과 readiness 확인을 통과해야 해당 대상의 기준이 갱신된다. 이력 불일치 차단은 설계대로 동작하고 있으며, 대상별 복구 절차가 끝나지 않은 상태다.

## 배포 기준과 확인 범위

이 문서의 적용 대상은 아래 앱과 Vault로 한정한다. 모니터링 배포와 설정 변경은 이번 작업에서 제외한다.
전체 자동 실행은 모니터링도 계획에 포함할 수 있으므로 이번 작업에서는 대상별 수동 배포와 변경 대상 계산을 구분한다.

| 대상 | 마지막 runtime 성공 기준 | 점검 결과 |
|---|---|---|
| Team | `ee68258a` | 현재 `main`의 조상이다. 컨테이너 readiness 200을 확인하였다. |
| Config, Gateway, Authorization, Channel, Project, Preference, Roadmap, Voice, Notification, Chat | `231bfea6` | 현재 `main`의 조상이 아니다. 이 값은 GitHub 배포 기준이며, 모든 VM의 현재 이미지 조회 결과를 뜻하지 않는다. |
| Vault | `10280762` | 현재 `main`의 조상이 아니다. 점검 당시 Vault는 unsealed·healthy 상태였다. |
| User | 없음 | 이전 이미지 `231bfea6`가 실행 중이며, 수동 배포 실패 후 별도 원인 확인이 필요하다. |

Voice, Notification, Chat은 SSH host key 불일치로 접속을 중단하였다. 사설망 readiness 200은 확인했으나 실행 이미지는 직접 확인하지 않았다. 배포 전에 패널의 VM 식별 정보와 신뢰한 host fingerprint를 대조한다.

같은 날 이후 패널에서는 Roadmap이 새 `gsmgpu2:150` VM으로 표시되었다. 앞선 `gsmgpu3:112` SSH 관측과 다른 대상이며, 새 VM의 실행 이미지는 아직 확인하지 않았다. [VM 이름과 서비스 배치 정리](../68-deployment/vm-placement-rebuild.md)에 새 식별자와 접속 정보 변경 범위를 기록한다.

배포 기준은 [`deploy/prod/deployment_state.py`](../../../../deploy/prod/deployment_state.py)의 `cowork-runtime` 성공 기록이다. 정적 검증, 설정 변경, 알림 작업의 성공 기록을 기준 복구에 사용하지 않는다. [`deploy/prod/build-matrix.py`](../../../../deploy/prod/build-matrix.py)는 대상별 기준과 배포 SHA의 조상 관계를 검사한다.

## 할 일

### 배포 입력 확정

- [`deploy/prod/inventory.json`](../../../../deploy/prod/inventory.json) 중 이번 범위의 대상마다 실제 VM, 서비스, SSH fingerprint, Vault 배포 문서가 일치하는지 대조한다.
- VM 재배치가 필요한 대상은 배치와 주소를 확정하고 Vault `secret/deploy/{target}`의 연결값과 runtime 값을 갱신한다.
- 적용할 현재 `main` 이력의 전체 SHA와 필요한 이미지를 확인한다. 문서에 기록한 축약 SHA를 실행 입력으로 사용하지 않는다.
- User는 관련 작업에서 실패 원인 확인에 필요한 자료를 확보한 뒤 재배포한다.

### 실제 적용과 기준 복구

- [`cowork-prod-cd.yml`](../../../../.github/workflows/cowork-prod-cd.yml)의 `operation=redeploy`, `check_only=true`로 대상별 설정을 정적 검증한다.
- 같은 입력의 `check_only=false`로 Vault, Config/Eureka, 앱 순서에 맞춰 수동 배포한다.
- 대상별 readiness, Eureka 등록, 필요한 projection 준비 상태를 수동 확인한다.
- 실제 적용이 성공한 대상의 `Prod-CD(<target>)`에 해당 SHA의 `cowork-runtime` 성공 기록이 생성되었는지 확인한다.
- 이번 범위의 기준을 복구한 뒤 자동 실행의 대상 계산에서 이력 불일치가 해소되는지 확인한다. 모니터링 적용을 수반하는 전체 실행은 이번 작업에 포함하지 않는다. 이력 검사 우회나 성공 기록의 수동 조작으로 기준을 바꾸지 않는다.

## 검증

- 대상별 적용 SHA, 실행 이미지, runtime 성공 기록을 대조한다.
- 각 성공 기준에 대해 `git merge-base --is-ancestor`로 현재 `main`과의 관계를 확인한다.
- 정적 검증과 설정 변경만 수행한 실행은 runtime 성공 기준을 변경하지 않는지 코드와 기록으로 대조한다.
- 변경 대상 계산에서 이번 범위의 대상 때문에 이력 불일치가 발생하지 않는지 확인한다.
- 문서와 배포 기록의 정적 대조 및 실제 운영 상태의 수동 조회로 확인한다. 회귀 테스트와 통합 테스트는 작성하거나 실행하지 않는다.

## 완료 조건

- 이번 범위의 모든 대상에서 실제 적용 SHA와 `cowork-runtime` 성공 기준이 일치한다.
- 해당 성공 기준이 현재 `main` 이력에 속하며 이 대상들 때문에 자동 대상 계산이 이력 불일치로 중단되지 않는다.
- 배포 완료 판정에는 실제 적용과 readiness 확인 결과가 사용되어 있다.
- VM 배치, Vault 연결값, 실행 서비스가 서로 일치한다.
