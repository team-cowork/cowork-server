# JVM Kafka outbox relay 정체와 장기 transaction 제거

- **서비스**: cowork-channel, cowork-team, cowork-project
- **우선순위**: 🔴 높음
- **현재 상태**: 짧은 claim·transaction 밖 발행·key별 순서·barrier·backoff·격리는 구현되어 있으며 공식 toolchain 빌드와 운영 확인이 남아 있다.

## 문제

세 서비스의 relay와 migration은 반영되어 있다. 기존의 장기 transaction 문제를 다시 구현 과제로
다루지 않는다. 공식 Java toolchain 빌드, 실제 데이터 migration과 여러 replica의 장애 복구는
아직 확인 결과가 없다.

구버전 writer는 producer fence를 사용하지 않는다. 혼합 배포는 허용하지 않으며
[운영 절차](../../../jvm-kafka-outbox-relay.md)에 따라 전환한다.

## 할 일

- 저장소에 지정한 Java toolchain으로 세 모듈을 빌드한다.
- 운영 데이터 사본에서 channel `V24`, team `V18`, project `V22`의 schema·barrier backfill을 확인한다.
- 모든 구버전 replica를 중지한 뒤 동일 버전으로 시작하는 배포 순서를 준비한다.
- 발행 지연·영구 오류·worker 중단·lease 만료·격리 재처리의 결과를 수동 운영 점검한다.
- 실제 Prometheus에서 backlog·격리·관측 실패를 확인하고 알림 기준을 정한다.

## 검증

- schema·fence row·미표시 completion marker를 운영 절차의 쿼리로 확인한다.
- 한 key의 실패가 다른 key를 막지 않고 completion barrier가 미완료 행을 추월하지 않는지 확인한다.
- 여러 replica의 claim 회수와 재처리 뒤 backlog·격리가 수렴하는지 관측한다.

## 완료 조건

- 공식 toolchain 빌드와 실제 데이터 migration 확인 결과가 기록되어 있다.
- 구·신 writer가 같은 DB에서 동시에 실행되지 않는다.
- 장애·격리·재처리의 순서와 복구 결과를 운영에서 확인할 수 있다.
