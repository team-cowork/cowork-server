# cowork-notification

## 역할

사용자 설정에 맞춰 푸시 알림과 실시간 알림을 전달합니다.

- Kafka 알림 요청 소비와 수신 대상·표시 정보 결정
- Firebase Cloud Messaging 푸시 발송과 디바이스 토큰 관리
- SSE 기반 실시간 알림 스트림 제공

## 스택

- Go / Chi
- Go modules + Makefile
- GORM / MySQL
- Kafka / Firebase Admin SDK / Eureka / Config Server

## 포트

| 용도       | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------|---------------|--------------------------|
| HTTP / SSE | `8086`        | `8086`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수             | 기본값                      | 설명                                                      |
|------------------|-----------------------------|-----------------------------------------------------------|
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결                                   |
| `APP_PROFILE`    | `local`                     | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용 |

- Config Server: 포트, Kafka topic·group, Eureka.
- Vault: `db.dsn`, 프로파일별 `fcm.credentials-json`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

## FCM 개별 전송 실패의 선택적 재시도

`notification.trigger`를 발행하는 `cowork-chat`·`cowork-project`·`cowork-team`은 논리 알림마다 안정적인
`eventId`를 함께 보낸다. `internal/infra/fcm.Sender.Send`는 FCM 응답을 `SUCCESS`·`INVALID`·`RETRYABLE`·
`UNCLASSIFIED`로 분류하고, `(eventId, deviceTokenId)`를 canonical key로 쓰는
`tb_notification_delivery_retry`에 첫 시도 전에 적재한다. `RETRYABLE`/`UNCLASSIFIED`로 끝난 토큰은
`internal/domain/delivery.Worker`가 지수 백오프(최대 6회, 미분류는 2회) 뒤 재전송하며, 재시도 전에 토큰이
삭제·교체됐으면 `CANCELLED`로 종료한다.

- `eventId`가 없는(아직 갱신되지 않은) producer는 원장에 적재하지 않고 기존처럼 1회만 시도한다.
- `IN_PROGRESS` 상태에는 그때 발급한 `claim_token`이 함께 저장되며, 이 값이 일치할 때만 최종 상태를 기록한다
  (fencing). 2분 넘게 `IN_PROGRESS`에 머문 행은 `ReclaimStale`이 `claim_token`을 지우고 `PENDING_RETRY`로
  되돌리므로, 뒤늦게 도착한 이전 claim의 finalize 호출은 조건이 맞지 않아 안전하게 무시된다.
- 종단 상태(`SUCCESS`/`INVALID`/`QUARANTINED`/`CANCELLED`)로 전환되는 즉시 `title`/`body`/`data_json`을
  비우고, worker가 10분마다 7일 지난 종단 행을 삭제한다.
- 운영 지표: `cowork_notification_fcm_delivery_outcomes_total`(outcome·source별),
  `cowork_notification_fcm_delivery_quarantined_total`(error_class별),
  `cowork_notification_fcm_delivery_pending`(현재 대기 건수).
- 복구: `QUARANTINED`로 격리된 행은 `tb_notification_delivery_retry`에서 원인(`last_error_class`)을 확인한 뒤
  운영자가 직접 재전송 여부를 판단한다. 자동 재격리 해제는 없다.
