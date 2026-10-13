# Production 배포 결과 표시 수정

- **서비스**: GitHub Actions, prod-cd
- **우선순위**: 🟠 중간
- **현재 상태**: 알림 작업의 `Production` 환경 성공 기록이 실제 배포 실패와 함께 표시되어 운영 배포 결과를 잘못 해석할 수 있다.
- **관련 작업**: [운영 배포 기준 SHA 복구](../62-deployment/runtime-deployment-baseline-recovery.md)

> **2026-10-13 진척:** `notify`의 `environment: Production` 연결을 제거하였다. 알림용 웹훅은 저장소 secret이므로 기존 이름으로 접근한다. YAML 구문과 `if: always()`, 웹훅 참조, 실제 배포 작업의 환경 연결을 정적으로 확인하였다. 실제 GitHub 실행에서 새 `Production` 기록이 생성되지 않는지는 아직 검증하지 않았다.

## 문제

2026-10-13 점검한 [User 배포 실행](https://github.com/team-cowork/cowork-server/actions/runs/38275443799)에서 `Prod-CD(user)`는 실패했으나 `Production`에는 성공 기록이 생성되었다. 워크플로 전체 결과와 Discord 알림의 상태는 실패였다. 같은 실행의 환경 표시와 실제 배포 결과가 다르다.

[`cowork-prod-cd.yml`](../../../../.github/workflows/cowork-prod-cd.yml)의 `notify` 작업은 `environment: Production`과 `if: always()`를 사용한다. 이 작업은 선행 작업의 실패를 알림 내용에 반영하더라도 알림 전송 자체가 성공하면 성공으로 끝난다. GitHub는 이 작업의 성공을 `Production` 환경 기록에 반영하므로 실제 운영 적용 성공으로 읽을 수 있는 표시가 남는다.

자동 배포 기준은 별도의 `cowork-runtime` 기록을 사용하므로 이 알림 성공이 배포 기준 SHA를 직접 바꾸지는 않는다. 다만 운영자가 환경 화면을 근거로 배포 완료를 판단하면 실패한 배포를 완료로 볼 수 있다. 릴리스 생성, 알림 전송, 실제 적용의 결과를 표시와 문서에서 구분할 필요가 있다.

## 결과 표시 기준

| 결과 | 의미 | 운영 적용 성공으로 사용하는가 |
|---|---|---|
| 이미지 빌드·릴리스 생성 성공 | 배포할 산출물이 존재한다. | 사용하지 않는다. |
| `check_only=true` 성공 | 배포 설정의 정적 검증을 통과하였다. | 사용하지 않는다. |
| `notify` 성공 | 알림 작업을 완료하였다. | 사용하지 않는다. |
| `cowork-runtime` 성공 | 해당 대상에 runtime을 적용하고 readiness를 통과하였다. | 대상별 적용 성공으로 사용한다. |

2026-10-13 GitHub API와 `gh secret list`로 확인한 결과, `Production` 환경의 secrets, variables, 보호 규칙은 비어 있고 배포 브랜치 정책도 없다. `DISCORD_INFORMATION_ALERT_CHANNEL_WEBHOOK`은 저장소 secret에 존재한다. 따라서 `notify`의 환경 연결을 제거해도 알림용 secret의 범위와 접근 방법은 유지된다. 비밀값 자체는 조회하거나 저장소와 문서에 기록하지 않았다.

운영 배포 완료는 대상별 `cowork-runtime` 성공 기록과 실제 readiness 결과를 함께 확인하여 판정한다. 과거 `Production` 성공 기록은 알림 성공일 수 있으므로 해당 실행의 대상별 배포 결과를 함께 확인한다.

## 할 일

### 알림 작업의 환경 연결 수정

- `Production` 환경에 연결된 알림용 설정과 보호 규칙을 확인한다.
- `notify`의 `environment: Production` 연결을 제거하고 저장소 secret에 있는 알림 설정을 그대로 사용한다.
- 전체 결과 표시가 필요하면 실제 배포 대상의 결과를 집계하는 별도 기준을 정한다. 실패와 취소, 배포 생략, 설정 변경만 수행한 실행을 실제 적용 성공으로 표시하지 않는다.
- 배포 실패 시에도 실패 알림을 전송하는 `if: always()`의 목적을 유지한다.

### 판정 근거 명시

- 운영 배포 문서에 대상별 `cowork-runtime` 기록과 실제 readiness를 완료 판정 근거로 명시한다.
- 과거 `Production` 성공 기록은 해당 실행의 대상별 배포 결과와 함께 해석하도록 기록한다.

## 검증

- 워크플로의 `notify` 환경 연결과 상태 집계 조건을 정적으로 대조한다.
- 기존 실패 실행의 대상별 배포 결과, 알림 결과, 환경 상태를 대조하여 문제의 원인을 확인한다.
- 수정 후 실제 운영 실행에서 알림 성공만으로 `Production` 적용 성공이 생성되지 않는지 수동 확인한다.
- 실패 알림의 상태와 워크플로 전체 결과가 일치하는지 확인한다.
- 회귀 테스트와 통합 테스트는 작성하거나 실행하지 않는다.

## 완료 조건

- 알림 전송 성공이 `Production` 배포 성공으로 기록되지 않는다.
- 배포 실패·취소·생략과 실제 적용 성공을 결과 표시에서 구분할 수 있다.
- 운영 배포 완료의 판정 근거가 대상별 runtime 적용과 readiness 결과로 문서화되어 있다.
