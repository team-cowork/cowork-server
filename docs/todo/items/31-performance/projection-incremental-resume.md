# 채팅 projection 증분 재개와 재구축 모드 분리

- **서비스**: cowork-chat
- **우선순위**: 🟠 중간
- **현재 상태**: shared Mongo checkpoint가 있어도 매 Kafka assignment마다 모든 projection partition을 retained low부터 다시 재생함

## 문제

일곱 projection consumer는 `fromBeginning: true`로 topic을 구독하고 `cowork-chat/src/common/kafka/projection-readiness.service.ts`의 `ProjectionReadinessService.registerProjection`에 등록된다. `ProjectionCheckpointRepository`가 partition별 `nextOffset`과 snapshot marker를 저장하지만, `claimForAssignment`는 새 owner가 lease를 얻을 때 `nextOffset`을 `earliestOffset`으로 덮어쓰고 현재 snapshot marker를 제거한다.

이어 `ProjectionReadinessService.claimAssignedPartitions`는 메모리 checkpoint를 broker의 `low`로 설정하고 `consumer.seek`도 같은 위치로 이동한다. 이 동작은 빈 MongoDB를 복구할 때뿐 아니라 정상 재시작과 rebalance에도 적용되므로 retained log 전체를 반복해서 MongoDB projection에 LWW merge한다.

`cowork-chat/src/main.ts`는 모든 projection이 새 high-watermark와 snapshot barrier까지 도달하기 전 일반 HTTP 요청을 `503`으로 차단하고, WebSocket 연결과 Eureka 등록도 보류한다. retention 구간이 길거나 snapshot 양이 많으면 정상 재시작만으로도 projection 쓰기 부하와 서비스 비가용 시간이 커지며, shared checkpoint는 assignment fencing 외에 재개 성능을 제공하지 못한다.

## 실행 모드

| 모드 | 진입 조건 | 시작 offset | readiness 조건 |
|------|-----------|-------------|----------------|
| 증분 재개 | projection dataset 세대가 일치하고 checkpoint가 retained 범위 안에 있음 | 저장된 `nextOffset` | 시작 시 캡처한 high-watermark까지 catch-up함 |
| 최초 bootstrap | projection dataset과 checkpoint가 모두 없음 | retained low | 유효한 전체 snapshot barrier와 high-watermark를 확인함 |
| 명시적 재구축 | 운영자가 dataset 초기화·topic 세대 교체·스키마 재생성을 요청함 | 검증된 rebuild 기준점 | 새 dataset의 전체 snapshot과 catch-up을 확인함 |
| 복구 불가 | checkpoint가 retention 밖이거나 dataset·checkpoint 세대가 다름 | 자동 seek하지 않음 | fail closed 후 명시적 재구축을 요구함 |

정상 증분 재개와 전체 재구축이 같은 assignment callback에 숨겨져 실행되지 않게 한다. checkpoint를 사용할 때는 해당 offset이 현재 MongoDB projection dataset과 같은 세대의 진행 위치라는 표식을 함께 검증한다.

## 할 일

### checkpoint와 dataset 세대

- projection collection의 dataset 세대, 초기화 완료 상태, topic·source 세대를 checkpoint와 연결해 저장한다.
- `ProjectionCheckpointRepository.claimForAssignment`가 정상 claim에서는 lease 필드만 CAS로 교체하고 기존 `nextOffset`과 유효한 recovery 상태를 보존하게 한다.
- checkpoint가 broker의 `[low, high]` 범위 안인지 확인한 뒤 `ProjectionReadinessService.claimAssignedPartitions`가 저장된 `nextOffset`으로 seek하게 한다.
- 빈 collection에 오래된 checkpoint만 남거나 collection만 남고 checkpoint가 없는 상태를 탐지해 증분 재개를 거부한다.
- rebalance 중 이전 owner의 active lease를 덮지 않는 현재 fencing 보장을 유지한다.

### 명시적 재구축

- 전체 재구축을 요청하고 대상 stream·dataset 세대·진행 상태를 확인할 관리 경로를 추가한다.
- 재구축 시작 전에 대상 projection과 checkpoint를 함께 초기화하거나 새 dataset에 구축해 stale key가 남지 않게 한다.
- 모든 partition에서 fresh full snapshot marker와 high-watermark를 확인한 뒤에만 새 dataset을 활성화한다.
- invalid record latch, retention gap, topic 세대 교체가 발생하면 자동 earliest replay 대신 재구축 필요 상태와 원인을 노출한다.
- 일부 stream만 재구축할 때 다른 projection과 API 가용성에 미치는 범위를 정의한다.

### readiness와 관측

- stream별 실행 모드, 시작 offset, 목표 high-watermark, 현재 checkpoint, lag, dataset 세대를 readiness 상세 상태로 제공한다.
- 정상 재시작에서는 기존에 완료한 snapshot barrier를 재사용하고 증분 catch-up 완료만으로 readiness를 열 수 있게 한다.
- full replay record 수, 증분 replay record 수, catch-up 소요 시간, rebuild 횟수를 분리해 측정한다.
- 운영 문서에 정상 재시작, retention gap, MongoDB 초기화, topic 세대 교체별 절차를 기록한다.

## 검증

- 정상 checkpoint가 있는 상태에서 프로세스를 재시작하면 retained low가 아니라 저장된 `nextOffset`부터 소비하는지 검증한다.
- consumer rebalance 후 새 owner가 이전 shared checkpoint 다음 record부터 처리하고 이미 적용한 전체 log를 다시 쓰지 않는지 검증한다.
- 빈 MongoDB와 빈 checkpoint에서는 전체 snapshot을 적용하기 전 readiness가 열리지 않는지 검증한다.
- checkpoint가 retention 밖이거나 dataset 세대가 다르면 자동 low replay 없이 명시적인 복구 불가 상태가 되는지 검증한다.
- 명시적 재구축이 stale projection을 제거하고 fresh snapshot과 high-watermark 확인 후에만 완료되는지 검증한다.
- 여러 replica와 여러 partition에서 lease fencing, offset 전진, readiness가 경합 없이 유지되는지 검증한다.

## 완료 조건

- 정상 재시작과 rebalance는 유효한 shared checkpoint에서 증분 재개한다.
- retained low부터의 전체 replay는 최초 bootstrap 또는 명시적 재구축에서만 실행된다.
- checkpoint와 MongoDB projection dataset의 세대 불일치가 탐지되어 잘못된 증분 재개를 허용하지 않는다.
- retention gap과 invalid 상태가 자동 merge replay로 숨겨지지 않고 재구축 필요 상태로 표시된다.
- readiness와 지표에서 stream별 증분 재개·bootstrap·재구축 상태를 구분할 수 있다.
- 증분 재개 이후에도 현재 assignment fencing과 snapshot 기반 복구 안전성이 유지되어 있다.
