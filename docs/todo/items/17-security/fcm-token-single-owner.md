# FCM device token 단일 계정 소유권 보장

- **서비스**: cowork-notification
- **우선순위**: 🔴 높음
- **현재 상태**: `tb_device_token`이 `(account_id, token)`만 유일하게 보장해 같은 FCM token을 여러 계정이 동시에 소유할 수 있음

## 문제

`cowork-notification`의 `tb_device_token`은 `uq_tb_device_token_account_token` 제약으로 계정과 token 조합만 중복을 막는다. `TokenRepository.Save`도 같은 두 컬럼을 conflict key로 사용하므로, 동일한 앱 설치 token을 다른 계정이 등록하면 새 행이 추가되고 이전 계정의 행은 유지된다.

FCM token은 일반적으로 앱 설치 인스턴스를 식별한다. 로그아웃·계정 전환 과정에서 클라이언트가 token을 회전시키거나 이전 계정에서 삭제하지 못하면, 현재 로그인한 기기가 이전 계정의 알림까지 수신할 수 있다. 같은 token이 여러 수신 대상에 포함되면 중복 푸시도 발생할 수 있다.

커밋된 `V1__init.sql`은 수정하지 않는다. 기존 중복 데이터의 소유자를 정하는 정리 정책과 새 유일성 제약을 후속 migration으로 적용한다.

## 소유권 정책

| 상황 | 목표 동작 |
|------|-----------|
| 같은 계정이 같은 token 재등록 | platform과 `updated_at`만 갱신함 |
| 다른 계정이 기존 token 등록 | 하나의 transaction에서 token 소유자를 새 계정으로 이전함 |
| 로그아웃 | 현재 계정과 token의 연결을 명시적으로 해제함 |
| FCM이 token 무효 응답 | token 소유 계정과 무관하게 해당 token 행을 제거함 |

## 할 일

### 스키마와 데이터

- 기존 중복 token별 최신 소유 행을 선택하는 정리 쿼리와 검증 보고서를 만든다.
- 새 migration에서 `token` 단독 unique 제약을 추가하고 기존 복합 unique 제약을 정리한다.
- GORM 모델의 index 선언을 실제 migration과 일치시킨다.

### 등록·해제 계약

- token 등록을 단일 계정으로 원자 재할당하는 upsert로 변경한다.
- 계정 전환과 로그아웃 시 클라이언트가 호출할 revoke 계약을 명확히 한다.
- 등록 요청의 `platform` 값을 `IOS`, `ANDROID`, `WEB` 중 하나로 검증한다.
- 소유권 변경과 무효 token 삭제를 token 원문 없이 관측할 수 있게 한다.

## 검증

- 허용 platform과 token 소유권 이전·해제 판단을 repository mock을 사용한 서비스 단위 테스트로 검증한다.
- 이전 계정 대상 알림에서 이전된 token을 제외하는 수신자 선택 규칙을 단위 테스트로 검증한다.
- token 단독 unique 제약과 upsert의 원자성은 schema·SQL 검토로 확인한다.
- 기존 중복 데이터 정리와 unique 제약 추가는 실제 데이터 사본의 migration dry-run으로 확인한다.
- 순차·동시 등록을 database에서 재현하는 자동화 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- 하나의 FCM token은 데이터베이스에서 최대 한 계정에만 연결되어 있다.
- 계정 전환 시 token 소유권이 원자적으로 새 계정으로 이동한다.
- 이전 계정의 알림이 현재 token으로 전달되지 않는다.
- 단일 소유권의 핵심 정책은 단위 테스트로, 동시 등록 경합은 database 제약과 운영 검증 절차로 보장되어 있다.
