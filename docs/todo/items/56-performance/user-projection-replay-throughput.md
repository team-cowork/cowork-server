# user projection 재생 처리량 개선

- **서비스**: cowork-user
- **우선순위**: 🟠 중간
- **현재 상태**: 운영 SQL debug 로그 차단과 Team·Presence의 최대 20건 transaction 묶음 처리를 구현하였다. 레코드별 검증·checkpoint 갱신은 유지하며, 운영 적용 후 처리량과 readiness 도달 시간 확인이 남아 있다.
- **관련 작업**: [채팅 projection 증분 재개와 재구축 모드 분리](../31-performance/projection-incremental-resume.md), [User 배포 readiness 실패 원인 확인](../64-reliability/user-deployment-readiness-failure.md)

## 문제

user는 `team.member.event`와 `user.presence.event`를 projection으로 소비한다. 두 토픽 모두 스냅샷 완료 마커를 받고 시작 시점의 끝 offset까지 따라잡아야 readiness를 연다(`cowork-user/lib/cowork_user/kafka/projection_readiness.ex`). 레코드마다 checkpoint 갱신, replay lease 연장, barrier 조회 등 여러 쿼리를 MySQL에 보낸다.

2026-10-02 user VM의 user는 cowork-db의 MySQL을 사용했다. 이 구성에서 두 토픽이 각각 초당 약 2건으로 진행되었다. 9/25 이후 밀린 약 4,000건과 5,200건을 따라잡는 데 약 50분이 걸렸다. debug 로그가 모든 SQL을 출력하고 있었으나, 지연 요인 중 로그와 원격 DB 왕복의 비중은 아직 측정하지 않았다.

재생 속도는 장애 복구 시간과 CD health 대기 시간을 직접 결정한다. 다른 projection 소비자도 같은 구조인지는 아직 확인하지 않았다.

## 구현 진척

- `cowork-user/config/runtime.exs`에서 `APP_PROFILE=prod`일 때 Logger의 전역 최소 수준을 `info`로 설정하였다. 기존 파일 backend의 `info` 필터만으로 차단되지 않던 콘솔 SQL `debug` 로그도 제외한다.
- 로컬 Docker와 운영 Docker가 모두 `MIX_ENV=prod`를 사용하므로 런타임 서비스 프로파일인 `APP_PROFILE`로 구분한다. `local`과 미지정 환경의 기존 로그 수준은 유지한다.
- 설정 파일의 프로파일별 평가와 형식 검사를 완료하였다. 운영 적용 후 로그 확인과 개선 전후 처리량 비교는 아직 수행하지 않았다.

## 진행 상태 (2026-10-13)

이전 이미지 `231bfea6`의 rollback 재시작 로그에서 Team·Presence는 각각 초당 약 `2.1`건을 처리하였고, 재생은 약 76분 동안 지속되었다. 1분 표본의 `COMMIT` 중앙값은 약 `49ms`였으며 일반 조회·갱신의 중앙값은 대체로 `0.2`~`0.5ms`였다. 이는 레코드별 commit 비용을 줄일 근거이다. 저장장치, 네트워크, Logger 각각의 기여도는 분리하지 않았다. 당시 후보의 초기 상태를 직접 확인하지 못해 후보의 readiness 실패 원인은 확정하지 않았다. 관측 구간과 로그 정책 차이는 [readiness 원인 문서](../64-reliability/user-deployment-readiness-failure.md)에 기록하였다.

[#491](https://github.com/team-cowork/cowork-server/pull/491)에서 Team·Presence consumer에만 `message_set` 수신과 최대 20건의 외곽 transaction을 적용하였다. 다른 consumer는 기존 단건 처리를 유지한다. 한 레코드의 projection, barrier, checkpoint는 기존에도 같은 transaction에서 처리되었다. 이번 변경은 여러 레코드의 commit 비용을 묶으며, 레코드별 checkpoint 갱신이나 쿼리를 생략하지 않는다.

기존 handler를 Kafka 순서대로 실행하며 업무 projection, marker, invalid latch, lease 검사를 유지한다. 중간 묶음이 실패하면 해당 묶음은 rollback되고 앞서 성공한 묶음의 projection과 checkpoint는 함께 보존된다. 전체 `message_set`이 성공한 뒤에만 brod에 commit을 반환한다. 재시작 시 generation 갱신, lease 정책, earliest 재생, readiness 조건은 변경하지 않았다. 이 변경은 checkpoint부터 재개하는 기능이 아니다.

변경한 네 파일의 `mix format --check-formatted`, `mix compile`, 공백 검사를 완료하였다. 의존성은 `mix.lock`에 잠긴 버전으로 받았으며 lock 파일은 변경하지 않았다. 회귀 테스트와 통합 테스트는 작성하거나 실행하지 않았다. 새 이미지의 운영 처리량, readiness 도달 시간, 콘솔 SQL 로그 차단과 Docker rotation 적용은 아직 확인하지 않았다. 이 과제는 부분 구현 상태이다.

## 할 일

- 레코드당 실행되는 쿼리 수와 운영 환경의 DB 왕복 지연을 측정한다.
- 운영 배포 후 콘솔과 파일 로그에 SQL debug 출력이 없는지 확인한다.
- 최대 20건 묶음 처리를 운영에 적용한 뒤 초당 처리량, transaction 지연, readiness 도달 시간을 기존 관측값과 비교한다. 레코드별 쿼리 수가 남은 병목인지 판단한다.
- channel, chat, notification의 재생 처리량도 같은 방식으로 측정한다.

## 검증

- 실제 배포의 재생량과 처리 구간을 함께 기록하여 개선 전후 처리량을 비교한다.
- 운영 재시작이 발생한 경우 generation 갱신과 earliest 재생을 거쳐 두 projection이 readiness 조건을 충족하는지 읽기 전용으로 확인한다. checkpoint부터 재개한다고 가정하지 않는다.
- 설정·코드는 정적으로 대조하고 운영 상태는 읽기 전용으로 확인한다. 회귀 테스트와 통합 테스트는 작성하거나 실행하지 않는다.

## 완료 조건

- 원격 DB 구성에서 user의 재생 처리량이 측정되어 있고 개선 목표치를 만족한다.
- 운영 user 로그에 SQL debug 출력이 없다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#457](https://github.com/team-cowork/cowork-server/pull/457).
- [대조 코드](../../../../cowork-user/lib/cowork_user/kafka/projection_processor.ex): 당시 prod Logger info 설정은 반영되었고, TeamMemberHandler는 레코드마다 ProjectionProcessor transaction과 checkpoint 갱신을 수행하였다.
- 당시 판정: **부분 구현**. DB 왕복·처리량 측정과 필요 쿼리·배치 개선을 남은 범위로 기록하였다. 이후 구현과 남은 범위는 위 `2026-10-13` 진행 상태와 할 일에 반영하였다.
