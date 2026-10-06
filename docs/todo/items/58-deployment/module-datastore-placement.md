# 모듈별 MySQL·Redis 배치 전환 검토

- **서비스**: MySQL, Redis, cowork-db VM, 모든 모듈 VM
- **우선순위**: 🟢 검토
- **현재 상태**: 모든 서비스가 cowork-db의 단일 MySQL·Redis를 공유하며, 인프라 담당과 합의한 모듈별 배치와 다르다

## 문제

현재 운영 MySQL 하나에 7개 DB(`cowork_<service>`)가 한 계정으로 들어 있다(`docs/deployment.md`). Redis, PostgreSQL, MongoDB, Elasticsearch, Kafka, Vault, Config, 모니터링도 모두 cowork-db에 모여 있다. 2026-10-02 복구 시 authorization, notification, user의 DB 접속 대상은 모두 cowork-db의 MySQL이었다.

인프라 담당과 2026-05에 합의한 구성은 다음과 같다. MySQL·Redis는 각 모듈 VM에 둔다. Kafka, Vault, Config/Eureka, 모니터링, 오브젝트 스토리지는 중앙에 둔다. VM 사양이 부족해 중앙 DB를 써야 하면 인프라 담당에게 보고해 사양을 조정한다. 현재 구성은 이 합의를 따르지 않고 있고, 사양 보고도 되어 있지 않다.

단일 DB VM은 디스크(여유 약 5%)와 장애 범위가 집중되어 있다. 원격 DB 왕복은 projection 재생 처리량에도 영향을 준다. 반대로 모듈별 배치는 VM 사양, 백업, 운영 부담을 늘린다.

## 선택지

| 방식 | 내용 | 고려 사항 |
|---|---|---|
| 모듈별 배치 | 합의대로 각 모듈 VM에 MySQL·Redis 배치 | VM 사양 상향 필요, 서비스별 백업·모니터링 |
| 중앙 유지 + 보고 | 사양 부족 사유를 인프라 담당에게 보고하고 중앙 유지 | 합의 절차 충족, 단일 장애 범위는 그대로 |
| 부분 분리 | 부하·중요도가 큰 서비스만 분리 | 구성 혼재로 운영 문서 복잡도 증가 |

## 할 일

- 모듈별 VM의 현재 사양과 MySQL·Redis 상주 시 필요한 자원을 산정한다.
- 선택지를 정해 인프라 담당에게 보고한다.
- 분리하는 경우 데이터 이전과 projection checkpoint 보존 순서를 설계한다.

## 검증

- 결정한 구성에서 각 서비스의 DB 접속 대상과 백업 경로를 확인한다.

## 완료 조건

- 운영 데이터 저장소 배치가 인프라 담당과 합의된 구성과 일치하거나, 예외 사유가 보고되어 있다.
