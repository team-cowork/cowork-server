# User 배포 readiness 실패 원인 확인

- **서비스**: cowork-user, Kafka, MySQL, prod-cd
- **우선순위**: 🔴 높음
- **현재 상태**: 이전 컨테이너의 약 76분 재생과 후속 정상 상태를 확인했고 Team·Presence의 최대 20건 transaction 묶음 처리를 구현하였다. 후보의 당시 실패 원인과 새 이미지의 운영 처리량·readiness 도달 시간은 아직 확인하지 않았다.
- **관련 작업**: [user projection 재생 처리량 개선](../56-performance/user-projection-replay-throughput.md), [운영 배포 기준 SHA 복구](../62-deployment/runtime-deployment-baseline-recovery.md)

> **2026-10-13 진척:** 이전 컨테이너의 보존 로그에서 재시작 후 약 76분 동안 projection 재생이 지속된 사실을 확인하였다. 후속 조회에서 이전 버전의 readiness는 200이었다. 묶음 처리는 구현했으며 generation·lease·earliest 재생·readiness 정책은 유지하였다. 새 이미지의 운영 효과는 아직 검증하지 않았다. 처리량 개선의 구현 범위와 남은 작업은 [성능 과제](../56-performance/user-projection-replay-throughput.md)에 기록한다.

## 문제

2026-10-13 점검한 [User 배포 실행](https://github.com/team-cowork/cowork-server/actions/runs/38275443799)에서 새 후보는 약 900초 동안 readiness를 통과하지 못했다. 이전 컨테이너를 다시 시작한 뒤에도 약 900초 동안 복구 완료를 확인하지 못하여 배포 스크립트가 실패로 종료되었다. 이 실행은 SSH 연결의 제한시간 초과로 중단된 것이 아니다.

실패 후보의 보존 로그에서는 `source_projections_not_ready` 상태가 반복되었다. [`profile_snapshot_publisher.ex`](../../../../cowork-user/lib/cowork_user/kafka/profile_snapshot_publisher.ex)는 원본 projection의 현재 상태를 확인한 뒤 프로필 snapshot을 발행한다. 이 메시지는 발행을 보류한 이유를 나타내며, 원본 projection이 준비되지 않은 원인까지 설명하지 않는다. 보존된 마지막 200줄만으로 당시 실패의 근본 원인을 확정하지 못했다.

점검 당시 실행 중인 이전 이미지 `231bfea6`에서는 `/actuator/health/readiness`가 200을 반환했고, Kafka consumer 연결과 Team·Presence projection 준비 완료를 확인하였다. 비교한 두 버전 사이에 해당 readiness 로직의 변경은 없었다. 현재 정상 상태가 과거 실패 원인을 설명하지는 않는다. 기존 처리량 과제에 기록된 느린 재생과 이번 실패의 인과관계도 아직 검증하지 않았다.

## 확인된 상태와 미확정 원인

| 항목 | 확인 결과 | 추가 확인 |
|---|---|---|
| 후보 readiness | 약 900초 동안 미통과 | 초기 기동부터 실패까지의 consumer·projection 상태 |
| 이전 컨테이너 복구 | 재시작 후 약 900초 안에 readiness를 확인하지 못함 | 실제 회복 시점과 재생 진행 상태 |
| 현재 이전 버전 | readiness 200, consumer 연결, 두 projection 준비 완료 | 다음 배포 시 같은 상태를 재확인 |
| 실패 후보의 보존 자료 | 마지막 200줄에 원본 projection 미준비 상태가 반복됨 | 연결, 할당, offset, marker, lease, DB 처리 지연 |
| 기존 처리량 병목과의 관계 | 미확정 | 재생량과 처리량을 같은 시간 기준으로 측정 |

[`deploy/prod/lib/container.sh`](../../../../deploy/prod/lib/container.sh)는 후보 실패 로그의 마지막 200줄을 VM에 보존하고 후보 컨테이너를 제거한다. 반복 메시지가 많으면 초기 원인 정보가 남지 않을 수 있다. 다음 적용 전에는 원인 확인에 필요한 기동 구간을 보존할 방법을 마련한다. 원문 로그와 비밀값은 GitHub Actions 출력이나 저장소 문서에 복사하지 않는다.

## 진행 상태 (2026-10-13)

이전 컨테이너의 Docker 로그를 읽기 전용으로 조회하였다. 대용량 파일은 시각을 기준으로 탐색하고 짧은 구간의 메시지 수, checkpoint, SQL 지연만 집계하였다. 아래 시각은 모두 `2026-10-13` KST이다. 로그 원문과 업무 데이터는 문서에 포함하지 않는다.

| 시각 | 확인한 상태 | 판정 |
|---|---|---|
| `01:25:21` | 이전 이미지 `231bfea6` 컨테이너 시작 | rollback 재시작 시점 |
| `01:25:48` | Team checkpoint `13720`, Presence checkpoint `20555`부터 처리 | 두 source의 보존 구간을 다시 재생 |
| `01:41:00`~`01:41:59` | Team `15607`→`15730`, Presence `22474`→`22599`, 발행 보류 메시지 28회 | 900초가 지난 뒤에도 재생 진행 |
| `02:30:00`~`02:30:59` | Presence `28645`→`28770`, 발행 보류 메시지 29회 | 재시작 후 65분이 지나도 재생 미완료 |
| `02:41:00`~`02:41:59` | Presence `30026`→`30085`, 마지막 발행 보류 `02:41:27`, 마지막 checkpoint 갱신 `02:41:28` | 약 76분의 재생 후 지속적인 발행 보류가 종료 |
| `02:42:00`~`02:45:59` | 발행 보류 없이 새 snapshot에 따른 소량의 checkpoint 갱신 | 지속 재생 구간 종료 확인; 당시 readiness HTTP 응답 시각은 미기록 |
| 후속 현재 상태 조회 | 동일 컨테이너 실행 중, `/actuator/health/readiness` 200 | 이전 버전의 현재 회복 확인 |

`01:35`, `01:40`, `01:41`, `01:45`, `02:00`, `02:15`의 각 1분 표본에서 Team은 약 124건, Presence는 약 126건의 checkpoint 갱신을 수행하였다. 두 source의 처리량은 각각 초당 약 2.1건이다. 이 구간의 offset은 한 단계씩 진행하므로 표본 내 처리 건수와 일치한다. 일반적인 compact 토픽에서는 offset 사이에 빈 구간이 있을 수 있으므로 전체 `end_offset - beginning_offset`를 메시지 수로 간주하지 않는다.

[`consumer.ex`](../../../../cowork-user/lib/cowork_user/kafka/consumer.ex)는 프로세스 시작마다 replay generation을 증가시킨다. [`projection_replay.ex`](../../../../cowork-user/lib/cowork_user/kafka/projection_replay.ex)는 assignment에서 Kafka earliest를 선택한다. [`projection_checkpoint.ex`](../../../../cowork-user/lib/cowork_user/kafka/projection_checkpoint.ex)는 해당 위치로 checkpoint를 재설정하고 기존 marker를 삭제한다. 따라서 후보가 실패한 뒤 이전 컨테이너를 시작하면 후보가 진행한 재생을 이어받지 않고 다시 시작한다. 이번 rollback의 900초 초과는 이 재생 과정이 실제로 계속되었다는 로그로 설명된다. 실패 후보도 같은 코드를 사용하지만 후보의 초기 상태가 없으므로 후보 실패 원인까지 확정하지 않는다.

SQL 지연은 전체 중앙값 약 `0.3ms`, 95백분위 약 `47ms`였다. `01:41` 표본에서 일반 조회·갱신의 중앙값은 대체로 `0.2`~`0.5ms`였고 `COMMIT`의 중앙값은 약 `49ms`였다. transaction 경계의 지연과 레코드별 처리를 후속 분석 대상으로 남긴다. 저장장치, 네트워크, Logger 각각의 기여도는 아직 분리하지 않았다.

### 이전 컨테이너의 로그 정책 차이

실제 컨테이너는 `json-file`을 사용하지만 `HostConfig.LogConfig.Config`가 비어 있다. 로그 파일은 약 `26.8GB`였다. 저장소의 [`deploy/prod/deploy.sh`](../../../../deploy/prod/deploy.sh)는 `231bfea6`, `ee68258a`, 현재 `main` 모두 `max-size=20m`, `max-file=5`를 지정한다. 따라서 저장소 CD의 rotation 옵션 누락이 아니라 기존 컨테이너와 선언된 배포 정책 사이의 차이이다. 이 파일 크기만으로 readiness 실패 원인이나 디스크 부족을 단정하지 않는다.

이전 이미지의 [`config.exs`](../../../../cowork-user/config/config.exs)는 파일 backend에만 `level: :info`를 지정하므로 콘솔 SQL debug 출력은 차단하지 않는다. 현재 `main`과 실패 후보 `ee68258a`에는 [`runtime.exs`](../../../../cowork-user/config/runtime.exs)의 `APP_PROFILE=prod` 전역 Logger `info` 설정이 이미 들어 있다. 현재 이전 이미지에는 적용되지 않았다. 새 이미지 적용 후 콘솔 수준과 실제 Docker rotation 설정을 함께 확인한다.

### 레코드 묶음 처리 구현

한 레코드의 projection, barrier, checkpoint는 기존에도 하나의 transaction에서 처리되었다. Presence 저장소의 중첩 transaction도 같은 DB transaction에 참여한다. 레코드 내부의 작업을 별도 transaction으로 나누어 처리한 문제가 아니다.

[#491](https://github.com/team-cowork/cowork-server/pull/491)에서 Team·Presence consumer만 `message_set`을 받고 최대 20건을 하나의 외곽 transaction에서 처리하도록 변경하였다. 다른 consumer는 기존 단건 처리를 유지한다. 기존 handler를 Kafka 순서대로 호출하므로 업무 projection 적용, 레코드별 checkpoint 갱신, marker, invalid latch, lease 검사를 그대로 수행한다. 각 묶음이 성공한 뒤 다음 묶음으로 진행하며, 전체 `message_set`이 성공한 뒤에만 brod에 commit을 반환한다. 중간 묶음이 실패하면 그 묶음의 변경은 함께 rollback되고 이전 묶음의 projection과 checkpoint는 함께 보존된다. generation 초기화, lease 정책, earliest 재생, readiness 조건은 변경하지 않았다. 구현과 남은 성능 검증 범위는 [처리량 개선 문서](../56-performance/user-projection-replay-throughput.md)를 기준으로 관리한다.

변경한 네 파일의 `mix format --check-formatted`와 공백 검사는 통과하였다. 최초 `mix compile`은 로컬에 Hex 의존성이 없어 중단되었다. 이후 `mix deps.get`으로 잠긴 버전을 내려받고 `mix.lock`의 SHA-256이 동일한지 확인한 뒤 `mix compile`을 완료하였다. 변경한 코드에서 컴파일 경고는 없었으며 기존 의존성과 빌드 설정의 경고는 남아 있다. 회귀 테스트와 통합 테스트는 작성하거나 실행하지 않았다. 실제 배포 후 처리량, readiness 도달 시간, 로그 정책을 확인하기 전까지 이 과제는 완료로 처리하지 않는다.

## 할 일

### 원인 자료 확보

- 다음 배포 전에 현재 이미지, readiness, consumer 연결, projection 상태를 같은 시각 기준으로 기록한다.
- 후보의 초기 기동부터 실패 또는 준비 완료까지 로그를 접근이 제한된 VM 파일에 보존한다.
- `team.member.event`와 `user.presence.event`의 partition 할당, 시작·끝 offset, 처리 checkpoint, snapshot 완료 marker, replay lease 상태를 읽기 전용으로 확인한다.
- 재생량, 초당 처리량, DB 왕복 지연을 측정하여 기존 처리량 과제와 연결되는지 판단한다.
- 후보와 이전 컨테이너의 실제 이미지, Config/Vault 설정 버전, 접속 대상을 대조한다.

### 원인에 따른 조치

- 확보한 근거로 연결 실패, marker·checkpoint 조건 미충족, 재생 처리량 부족, 설정 차이 중 실제 원인을 구분한다.
- 확인한 원인을 해당 코드 또는 운영 설정에서 수정한다. readiness 조건을 제거하거나 대기시간만 늘려 실패를 감추지 않는다.
- 처리량 부족이 확인되면 관련 성능 과제에 측정값과 필요한 개선을 반영한다.
- 정상적인 초기화에 필요한 시간을 측정한 뒤 `HEALTH_TIMEOUT_SECONDS`를 정한다. 후보 대기와 복구 대기, 이미지 준비 시간을 합산하여 SSH `command_timeout`과 대조한다.
- 재배포 후 readiness와 두 projection 준비 상태를 확인하고 성공한 경우에만 runtime 배포 기준을 갱신한다.

## 검증

- 로그 시각, offset 변화, DB 처리량을 대조하여 readiness 실패 원인을 설명할 수 있는지 확인한다.
- 실제 배포에서 후보의 `/actuator/health/readiness`, consumer 연결, 두 projection 준비 상태를 수동 확인한다.
- 복구가 발생한 실제 실행은 이전 컨테이너의 재시작과 readiness 회복을 각각 확인한다. 컨테이너 실행 상태만으로 복구 완료를 판정하지 않는다.
- 설정과 배포 기록은 정적으로 대조하고 운영 상태는 읽기 전용으로 조회한다. 핵심 업무 로직 수정이 필요한 경우에만 해당 로직의 단위 테스트를 작성·실행한다. 회귀 테스트와 통합 테스트는 작성하거나 실행하지 않는다.

## 완료 조건

- readiness 실패 원인이 관측 자료로 확인되어 있고 원인에 맞는 조치가 적용되어 있다.
- User 후보가 필요한 projection 준비를 마친 뒤 정상 readiness를 반환한다.
- 배포 실패 시 복구 완료 여부가 readiness 결과로 구분되어 있다.
- 준비 조건을 우회하지 않으며 단순 대기시간 연장만으로 문제를 완료 처리하지 않는다.
