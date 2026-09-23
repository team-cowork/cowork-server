# 종료 음성 세션의 Redis stale cache 차단

- **서비스**: cowork-voice
- **우선순위**: 🔴 높음
- **현재 상태**: 완료. 음성 세션을 MongoDB에서 직접 조회하고 갱신한다.
- **결론**: 세션 캐시를 제거해 무효화 실패와 지연된 캐시 쓰기의 원인을 없앴다. 캐시용 tombstone, repair worker, 복구 metric·runbook은 후속 작업으로 남기지 않는다.

## 문제

기존 구현은 active 음성 세션을 channel, room name, session ID별 Redis key에 2시간 동안 저장했다. MongoDB에서 세션을 종료한 뒤 Redis 삭제가 실패하거나 늦은 캐시 쓰기가 실행되면, 이미 종료된 세션이 active 조회로 반환될 수 있었다.

PR #396의 초기 수정은 cache hit마다 MongoDB 상태를 재확인했다. 모든 조회에서 MongoDB 접근이 필요해 Redis 조회·저장·삭제 비용만 추가되는 구조였다.

## 반영 내용

- `cowork-voice/cmd/server/main.go`에서 음성 서비스와 webhook에 MongoDB repository를 직접 주입한다.
- `FindActiveSession`은 `channel_id`와 `status=active`로 조회한다. `Join`, `Leave`, `GetParticipants`는 이 repository 계약을 공통으로 사용한다.
- `FindSessionByRoomName`은 종료된 세션도 반환해 종료 후 도착한 webhook을 처리할 수 있게 한다. `room_name` unique index로 조회를 지원한다.
- Redis session repository와 클라이언트 초기화·종료 코드를 제거한다. `Join`에만 있던 캐시 방어 분기와 해당 분기 전용 테스트도 제거한다.
- 음성 서비스의 Redis 설정, local·prod 실행 환경변수, Compose 기동 의존성과 서비스 문서를 정리한다.
- LiveKit에서 사용하는 Redis 라이브러리는 Go 간접 의존성으로 유지한다.

## 검증

- `cowork-voice`에서 `go test -count=1 ./internal/domain/voice_room ./internal/domain/webhook`으로 핵심 서비스 단위 테스트를 실행했고 통과했다. 새 세션 입장 시 발급 토큰과 응답이 해당 세션의 room을 가리키는 검증을 보강했다.
- `cowork-voice`에서 `go build ./...`와 `go vet ./...`가 통과했다.
- `bash -n deploy/local/services/voice.sh deploy/prod/services/voice.sh`와 `docker compose -f deploy/compose/stack.yaml config --no-interpolate --no-env-resolution --quiet`가 통과했다.
- 런타임의 Redis session cache 참조가 제거된 것을 정적으로 확인했다. MongoDB·Redis를 기동하는 통합·회귀 테스트는 실행하지 않았다.

## 완료 조건

- 활성 음성 세션 조회는 MongoDB의 저장된 상태를 기준으로 한다.
- 세션 캐시 조회·저장·무효화 경로가 없어 stale cache가 세션 선택에 관여하지 않는다.
- 음성 애플리케이션은 Redis 연결 없이 기동하고 세션을 처리한다.
- 캐시 마이그레이션이나 복구 워커를 요구하는 후속 작업이 남아 있지 않다.
