# user projection 재생 처리량 개선

- **서비스**: cowork-user
- **우선순위**: 🟠 중간
- **현재 상태**: 원격 MySQL 환경에서 projection 재생이 토픽당 초당 약 2건으로 진행되어 장기 중단 후 readiness가 수십 분 닫힌다
- **관련 작업**: [CD health 대기와 SSH 명령 제한 분리](../53-deployment/cd-health-wait-ssh-timeout.md), [채팅 projection 증분 재개와 재구축 모드 분리](../31-performance/projection-incremental-resume.md)

## 문제

user는 `team.member.event`와 `user.presence.event`를 projection으로 소비한다. 두 토픽 모두 스냅샷 완료 마커를 받고 시작 시점의 끝 offset까지 따라잡아야 readiness를 연다(`cowork-user/lib/cowork_user/kafka/projection_readiness.ex`). 레코드마다 checkpoint 갱신, replay lease 연장, barrier 조회 등 여러 쿼리를 MySQL에 보낸다.

2026-10-02 user VM의 user는 cowork-db의 MySQL을 사용했다. 이 구성에서 두 토픽이 각각 초당 약 2건으로 진행되었다. 9/25 이후 밀린 약 4,000건과 5,200건을 따라잡는 데 약 50분이 걸렸다. debug 로그가 모든 SQL을 출력하고 있었으나, 지연 요인 중 로그와 원격 DB 왕복의 비중은 아직 측정하지 않았다.

재생 속도는 장애 복구 시간과 CD health 대기 시간을 직접 결정한다. 다른 projection 소비자도 같은 구조인지는 아직 확인하지 않았다.

## 구현 진척

- `cowork-user/config/runtime.exs`에서 `APP_PROFILE=prod`일 때 Logger의 전역 최소 수준을 `info`로 설정하였다. 기존 파일 backend의 `info` 필터만으로 차단되지 않던 콘솔 SQL `debug` 로그도 제외한다.
- 로컬 Docker와 운영 Docker가 모두 `MIX_ENV=prod`를 사용하므로 런타임 서비스 프로파일인 `APP_PROFILE`로 구분한다. `local`과 미지정 환경의 기존 로그 수준은 유지한다.
- 설정 파일의 프로파일별 평가와 형식 검사를 완료하였다. 운영 배포 후 로그 확인, 로그 차단 전후 처리량 비교, DB 왕복 지연 측정은 아직 수행하지 않았다.

## 할 일

- 레코드당 실행되는 쿼리 수와 운영 환경의 DB 왕복 지연을 측정한다.
- 운영 배포 후 콘솔과 파일 로그에 SQL debug 출력이 없는지 확인한다.
- 순서·멱등성 규칙(`.claude/rules/kafka-projections.md`)을 유지하는 범위에서 여러 레코드를 한 transaction으로 적용하고 checkpoint를 묶어 갱신하는 방식을 검토한다.
- channel, chat, notification의 재생 처리량도 같은 방식으로 측정한다.

## 검증

- 동일한 밀린 양을 재생할 때 개선 전후 처리량을 비교한다.
- 재생 중 중단·재시작 후 checkpoint부터 정상적으로 이어지는지 확인한다.

## 완료 조건

- 원격 DB 구성에서 user의 재생 처리량이 측정되어 있고 개선 목표치를 만족한다.
- 운영 user 로그에 SQL debug 출력이 없다.
