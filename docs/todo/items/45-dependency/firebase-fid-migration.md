# Firebase 최신 SDK 적용을 위한 FCM 식별자 FID 전환

- **서비스**: cowork-notification, iOS·Android·Web 클라이언트
- **우선순위**: 🟠 중간
- **현재 상태**: registration token 기반 구현으로 Firebase Admin Go `4.20.0`을 사용하고 있으며, `4.21.0`의 FID 전환은 반영하지 않았다
- **관련 작업**: [FCM device token 단일 계정 소유권 보장](../17-security/fcm-token-single-owner.md), [FCM 개별 전송 실패의 선택적 재시도](../33-reliability/fcm-partial-failure-retry.md)

## 문제

2026-09-16 확인한 최신 Firebase Admin Go `4.21.0`은 Firebase Installation ID(FID) 전송을 지원하고 `messaging.MulticastMessage.Tokens`를 deprecated 처리한다. 기존 필드가 제거된 것은 아니지만, 현재 전송 코드로 SDK만 올리면 정적 검사에서 `SA1019`가 발생한다. 이번 의존성 갱신에서는 `cowork-notification/go.mod`의 SDK를 `4.20.0`으로 유지하고 다른 직접·전이 의존성을 갱신했다. 근거는 [공식 릴리스](https://github.com/firebase/firebase-admin-go/releases/tag/v4.21.0)와 [SDK의 multicast 구현](https://github.com/firebase/firebase-admin-go/blob/v4.21.0/messaging/messaging_batch.go)이다.

현재 `POST /notifications/tokens`는 `token`과 `platform`을 받아 `tb_device_token.token`에 저장한다. `FCMSender.Send`도 token 문자열을 받아 `MulticastMessage.Tokens`로 전송한다. registration token과 FID는 서로 다른 값이므로 필드명을 `Fids`로 바꾸거나 기존 DB 값을 복사하는 방식으로 전환할 수 없다.

클라이언트가 실제 FID를 확보·등록하는 계약과 서버의 저장·전송 계약을 함께 바꿔야 한다. iOS·Android·Web 클라이언트 저장소와 각 플랫폼의 FID 전송 전제 조건은 아직 점검하지 않았다. 선택적 재시도도 FID 기반 설치 레코드를 대상으로 동작하도록 변경한다.

## 전환 기준

서버·클라이언트를 FID 전용 계약으로 일괄 전환한다. 구형 클라이언트와 token API의 하위 호환, token·FID 병행 전송, fallback은 제공하지 않는다. 기존 token 등록과 그에 연결된 전송 상태의 보존·이관은 요구하지 않으며, 클라이언트가 실제 FID를 새로 등록한다. 신규 FID 전송에는 단일 계정 소유권과 선택적 재시도 규칙을 적용한다.

## 변경 범위

아래 경로는 `cowork-notification/` 기준이다.

| 영역          | 현재 구현                                                                                                                       | 결정·변경할 내용                                                             |
|---------------|---------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| 등록·삭제 API | `internal/domain/token/handler.go`, `cmd/server/main.go`의 `POST /notifications/tokens`, `DELETE /notifications/tokens/{token}` | FID 전용 등록·해제 계약, token 계약 제거, Swagger 설명                      |
| 도메인·저장   | `internal/domain/token/model.go`, `ports.go`, `service.go`, `internal/infra/mysql/token_repository.go`                          | FID 기반 설치와 계정의 연결, 중복 등록·소유권 이전 정책                     |
| 스키마        | `src/main/resources/db/migration/V1__init.sql`, `V7__add_notification_delivery_retry.sql`                                       | 후속 migration으로 FID 스키마 적용, 기존 token 등록·연결 전송 상태 정리     |
| FCM 전송      | `internal/infra/fcm/sender.go`                                                                                                  | `MulticastMessage.Fids` 사용, 배치 구성과 결과를 설치 식별자에 연결하는 방식 |
| 선택적 재시도 | `internal/domain/delivery/`, `internal/infra/mysql/delivery_repository.go`                                                      | 신규 FID 설치를 기준으로 전송 식별, 삭제·계정 변경 시 대기 전송 처리       |
| 버전·관측     | `go.mod`, `go.sum`, `internal/monitoring/delivery_metrics.go`                                                                   | SDK 고정 해제, 전환 현황 관측                                                |

FID만 최대 500개씩 배치로 전송하고, SDK 응답 순서를 요청의 FID와 설치 레코드에 정확히 대응시킨다.

## 할 일

### 클라이언트와 API 계약

- 플랫폼별 공식 SDK의 FID 획득 방법, FCM 전송 전제 조건과 필요한 클라이언트 버전을 확인한다.
- 클라이언트의 최초 등록, 재설치·식별자 갱신, 로그아웃·계정 전환 시 등록·해제 흐름을 정의한다.
- 등록·해제 API를 FID 전용 계약으로 변경하고 기존 token 요청·응답과 호환 분기를 제거한다.
- 실제 FID만 신규 계약으로 저장하도록 요청·도메인 모델을 변경하고 Swagger와 클라이언트 연동 문서를 갱신한다.

### 데이터와 전송 상태

- 기존 token 등록과 연결된 전송·재시도 상태를 참조 관계에 맞춰 정리하고, 클라이언트의 FID 신규 등록으로 전송 대상을 구성한다.
- 적용된 migration 이력은 유지하고 후속 migration으로 FID 컬럼·제약을 적용한다. 불필요한 token 컬럼과 코드는 이번 전환에서 제거한다.
- 단일 계정 소유권은 관련 17번 작업의 정책과 일치시키고, FID 도입 이후에도 이전 계정의 알림이 현재 설치로 전달되지 않게 한다.
- 신규 FID 설치 레코드 ID와 `eventId`를 전송 식별 기준으로 삼고, 선택적 재시도 원장·worker·쿼리를 이에 맞춘다.
- 전송 대상·결과 모델과 무효 식별자 삭제 경로를 갱신하고 배치 응답을 원래 설치 레코드에 대응시킨다.

### SDK와 배포 전환

- 적용 시점의 최신 안정 SDK와 전이 의존성 호환성을 재확인한 뒤 `go.mod`·`go.sum`을 갱신한다.
- FID 전송으로 전환하고 token 경로와 임시 버전 고정 주석을 제거한다. 정적 검사 제외로 deprecated 사용을 숨기지 않는다.
- 식별자 원문을 로그·metric label에 남기지 않으면서 FID 등록 현황, 전송 결과, 재시도 적체를 확인할 수 있게 한다.
- 서버·클라이언트 일괄 전환, 기존 token 등록·연결 전송 상태 정리와 FID 신규 등록 순서를 기록한다.

## 검증

- 핵심 비즈니스 규칙인 수신자 선택, mute, 대상 계정·설치 소유권 판단만 서비스 단위 테스트로 검증한다.
- `go build`, `go vet`, `golangci-lint`로 최신 SDK에서 컴파일과 정적 검사가 통과하는지 확인한다.
- 배치 한도·응답 순서·오류 분류는 공식 SDK 계약과 구현을 대조한다. schema·쿼리 검토로 token 데이터 정리와 신규 FID 설치·재시도 참조가 일치하는지 확인한다.
- 실제 클라이언트의 FID 등록·수신, 계정 전환·등록 해제와 신규 FID 대상 재시도는 검증 환경에서 수동 확인한다.
- FCM 네트워크 호출, DB migration, 배치 조립과 retry worker를 고정하는 자동화 통합·회귀 테스트는 작성하거나 실행하지 않는다.

## 완료 조건

- 적용 시점의 최신 호환 안정 Firebase Admin Go SDK를 사용하고 deprecated 전송 필드에 대한 정적 검사 제외가 없다.
- 실제 FID가 등록·저장·전송되며 기존 token을 FID로 취급하지 않는다.
- 서버·클라이언트가 FID 전용 계약을 사용하며 token API·컬럼·전송 경로와 호환 분기가 남아 있지 않다.
- 기존 token 등록과 연결된 전송 상태가 정리되어 있고, 신규 FID 설치별 전송 식별과 선택적 재시도가 동작한다.
- 등록·해제·계정 전환 계약, schema와 API 문서가 구현과 일치하고 빌드·정적 검사·핵심 비즈니스 단위 테스트가 통과한다.
