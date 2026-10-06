# 서비스 장기 중단 감지와 알림

- **서비스**: 모니터링, GitHub Actions, 모든 운영 서비스
- **우선순위**: 🟠 중간
- **현재 상태**: user가 9/25부터 중단되어 있었으나 감지되지 않았고, CD 알림은 CI 실패 시에도 성공으로 보고된다
- **관련 작업**: [실행 중 릴리스를 보존하는 디스크 정리](../44-deployment/release-retention.md), [서비스 로그 수집 경로와 필드 정규화](../43-monitoring/log-collection-contract.md)

## 문제

2026-10-02 복구 과정에서 user projection의 마지막 스냅샷 마커가 2026-09-25로 확인되었다. user VM과 team VM에는 Docker가 설치되어 있지 않았다. 여러 서비스가 일주일 가까이 중단된 상태였지만 누구도 알지 못했다. 이 중단 기간이 Kafka projection의 밀린 양을 키워 복구 시간을 늘렸다.

`cowork-prod-cd.yml`의 notify job은 `if: always()`로 실행되며, CI 실패로 배포가 진행되지 않은 경우에도 성공으로 보고된다. 그래서 Discord 알림만으로는 배포 실패를 알아차릴 수 없다.

cowork-db의 디스크 여유 공간은 약 5%(3.8GB)였다. MySQL, Kafka, Elasticsearch, Vault가 같은 디스크를 쓰므로, 공간이 고갈되면 전체 장애로 이어진다.

## 할 일

- Eureka 등록 목록 또는 각 서비스 readiness를 주기적으로 확인해 기대 서비스 집합과 다르면 알린다.
- Vault sealed 상태와 Config Server 5xx를 알림 대상에 포함한다.
- notify job이 실제 배포 결과(성공·실패·미실행)를 구분해 보고하도록 바꾼다.
- cowork-db 디스크 사용률 알림을 추가하고 현재 여유 공간을 확보한다.

## 검증

- 서비스 하나를 중지했을 때 정해진 시간 안에 알림이 오는지 확인한다.
- CI 실패 후 CD 알림이 실패 또는 미실행으로 표시되는지 확인한다.

## 완료 조건

- 운영 서비스 중단, Vault seal, 디스크 부족이 알림으로 감지된다.
- CD 알림의 상태가 실제 배포 결과와 일치한다.
