# 서비스 장기 중단 감지와 알림

- **서비스**: 모니터링, GitHub Actions, 모든 운영 서비스
- **우선순위**: 🟠 중간
- **현재 상태**: 등록된 target의 down·5xx·adapter 장애 알림과 CD job 결과 집계는 구현되어 있으나, 서비스 소실·Vault seal·디스크 부족과 상위 CI 실패 구분은 남아 있다.
- **관련 작업**: [실행 중 릴리스를 보존하는 디스크 정리](../44-deployment/release-retention.md), [서비스 로그 수집 경로와 필드 정규화](../43-monitoring/log-collection-contract.md)

## 문제

기존 `coworkServiceDown`은 `up{job=~"cowork-.*"} == 0`을 검사한다. Eureka에서 제거되어 target 자체가
사라진 서비스는 이 조건에 걸리지 않는다. 기대 서비스 목록과 실제 발견 목록의 차이를 감지해야 한다.
Vault seal 전용 관측·호스트 디스크 사용량 수집과 알림도 현재 운영 설정에서 확인되지 않는다.

CD는 이미 모든 배포 job의 실패·취소를 집계한다. 남은 공백은 상위 `workflow_run.conclusion`이다.
CI 실패로 CD job이 모두 `skipped`이면 `Determine Status`의 초기값 `success`가 유지된다.
배포 job 실패 처리와 상위 CI 실패·미실행 처리를 구분해 보완한다.

2026-10-02에 기록한 서비스 중단 기간과 디스크 여유 5%는 과거 관측치다.
이번 점검에서 현재 VM의 가용성과 디스크 사용량은 직접 확인하지 않았다.

## 코드 근거

- [알림 규칙](../../../../deploy/config/monitoring/prometheus/rules/application-alerts.yml): down·5xx·adapter·Kafka lag·DB pool 규칙은 있으나 기대 집합·seal·filesystem 규칙은 없다.
- [수집 대상 생성](../../../../deploy/prod/monitoring/render-prometheus.py): 앱 target과 health probe가 Eureka discovery에 의존한다.
- [CD 결과 집계](../../../../.github/workflows/cowork-prod-cd.yml): `needs.*.result`를 순회하지만 상위 CI 결론과 전부 skipped인 경우를 판정하지 않는다.

## 할 일

- 기대 서비스 집합과 discovery 차이를 감지하고 장기 미등록 상태를 알린다.
- Vault seal·Config 장애와 호스트 디스크 사용량의 수집·알림 경로를 마련한다.
- 상위 CI 실패·취소·배포 미실행을 CD 알림에 명시한다.
- 현재 디스크 여유를 측정하고 필요한 정리는 릴리스 보존 작업과 함께 수행한다.

## 검증

- 등록 제거된 서비스도 정해진 시간 안에 알림이 오는지 수동 확인한다.
- 상위 CI 실패와 배포 job 실패가 각각 정확한 상태로 표시되는지 확인한다.
- 과거 중단·디스크 수치를 현재 관측 결과로 갱신한다.

## 완료 조건

- target이 사라진 서비스, Vault seal, 디스크 부족을 감지한다.
- CD 알림이 상위 CI 결과와 실제 배포 실행 여부를 구분한다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#348](https://github.com/team-cowork/cowork-server/pull/348) · [#349](https://github.com/team-cowork/cowork-server/pull/349) · [#449](https://github.com/team-cowork/cowork-server/pull/449).
- [대조 코드](../../../../.github/workflows/cowork-prod-cd.yml): down·5xx·adapter 알림과 배포 job 결과 집계는 있다. 소실 target·seal·디스크와 CI 실패로 모든 CD job이 skipped인 경우는 처리하지 않는다.
- 판정: **부분 구현**. 기대 서비스 집합·인프라 알림과 상위 CI 결론 반영을 남은 범위로 유지한다.
