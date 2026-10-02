# Preference status 만료 작업의 Redis lock 의존 제거

- **서비스**: cowork-preference, Redis
- **우선순위**: 🟠 중간
- **현재 상태**: Redis lock 요청이 실패하면 해당 회차의 status 만료 처리가 실행되지 않음
- **파생 원본**: [Preference Redis cache 실패 격리](../23-reliability/preference-cache-failure-isolation.md)

## 문제

`MainVerticle.checkExpiredStatuses`는 60초마다 `PreferenceCache.acquireExpiryLock`으로 Redis `SET NX EX` lock을 얻은 뒤에만 만료된 계정 status를 정리한다. lock 요청은 만료 처리를 감싼 `runCatching` 바깥에서 호출되므로, Redis 오류가 나면 예외가 그대로 전파되고 그 회차의 만료 처리는 실행되지 않는다.

Redis 장애가 이어지는 동안에는 `status_expires_at`이 지난 계정 status가 해제되지 않고 `preference.status.changed` 이벤트도 발행되지 않는다. Redis cache 장애가 Preference 쓰기 작업을 멈추게 하는 경로가 남아 있는 셈이다.

만료 대상 조회(`PreferenceRepository.findExpiredAccountStatuses`)는 `FOR UPDATE`로 행을 잠그고, `clearExpiredStatuses`는 `status`와 `status_expires_at`을 함께 지운다. 따라서 Redis lock은 정합성 장치라기보다 replica 간 중복 조회를 줄이는 최적화에 가깝다. 다만 lock 없이 겹쳐 실행될 때 이벤트가 중복 적재되지 않는지는 아직 검증하지 않았다.

## 할 일

- lock 획득 실패를 만료 처리 실패와 분리하고, Redis를 쓸 수 없으면 lock 없이 만료 처리를 진행한다.
- lock 획득 실패를 민감한 값 없이 metric과 로그로 기록한다.

## 검증

- lock 없이 겹친 실행에서 뒤 transaction이 이미 처리된 행을 건너뛰는지 `FOR UPDATE` 조회 조건과 갱신 내용을 정적으로 점검한다.
- Redis와 database를 구동하는 자동화 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- Redis 장애 중에도 만료 시각이 지난 계정 status가 해제되고 `preference.status.changed`가 발행된다.
- 겹친 만료 실행이 같은 계정의 status 변경 이벤트를 중복 적재하지 않는다.
