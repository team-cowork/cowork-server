# FCM device token 단일 계정 소유권 보장

- **서비스**: cowork-notification
- **우선순위**: 🔴 높음
- **현재 상태**: FID 단독 unique·원자 소유권 이전·최초 전송과 재시도 직전의 세대 검증과 수신 측 계정 검증용 `accountId` payload는 구현되어 있으며 data-only 메시지 전환과 클라이언트 적용이 남아 있다.

## 문제

`tb_device_installation`은 FID 단독 unique로 설치 하나를 한 계정에만 연결한다. registration token 시절의
`tb_device_token`과 그 중복 정리 결과는 `V10__replace_device_token_with_fid_installation.sql`이 이관 없이 제거했다.

계정 이전 때 바뀌는 `installation_id`가 이전 계정의 대기 FCM 재시도를 취소하는 경계다.
최초 전송은 전송 원장 claim 뒤 FCM 호출 직전에 수신자 설치를 다시 읽어 같은 `installation_id`·FID
세대만 보내고, 사라진 세대의 claim은 `CANCELLED`로 종결한다.
최초 전송과 재시도 모두 검증 직후 이전되는 경우나 이미 진행 중인 외부 전송을 취소하는 보장은 없다.

## 검증 이후 이전 정책

서버는 FCM에 넘긴 메시지를 회수할 수 없으므로 검증과 전송 사이의 이전·이미 진행 중인 요청은 서버에서 막지 않는다.
대신 모든 FCM 메시지의 data payload에 대상 설치 세대를 소유한 계정 ID를 `accountId`(10진 문자열)로 넣는다.
`installation_id` 세대의 소유 계정은 바뀌지 않으므로 최초 전송은 직전 재조회 결과, 재시도는 `CurrentFID` 결과의 계정을 쓴다.
클라이언트는 `accountId`가 현재 로그인한 계정과 다르거나 로그아웃 상태이면 메시지를 표시하지 않는다.

현재 메시지는 `Notification`(title·body)을 포함한다. 앱이 백그라운드이면 Android 시스템 트레이·iOS APNs alert·
Web FCM service worker가 앱 코드보다 먼저 알림을 표시하므로, 수신 측 검증은 앱이 포그라운드일 때만 표시를 막는다.
백그라운드 표시까지 막는 방법은 title·body를 data로 옮긴 data-only 메시지와 클라이언트의 `accountId` 검증 뒤
로컬 알림 생성이다. 서버만 먼저 바꾸면 갱신 전 클라이언트는 백그라운드 알림을 표시하지 못하고, iOS는
data-only 푸시의 백그라운드 전달이 제한되므로 두 변경은 함께 배포한다. 그 전까지는 검증 직후 이전된 설치에서
이전 계정의 title·body가 백그라운드 알림으로 표시될 수 있다.

## 코드 근거

- [설치 소유권 이전](../../../../cowork-notification/internal/infra/mysql/installation_repository.go#L22): FID 잠금 후 다른 계정의 행을 재생성하여 설치 세대를 교체한다.
- [재시도 전 검증](../../../../cowork-notification/internal/domain/delivery/worker.go#L156): CurrentFID로 사라진 세대를 취소한 뒤 소유 계정을 `accountId`로 실어 FCM을 호출한다.
- [최초 전송 직전 검증](../../../../cowork-notification/internal/domain/installation/service.go#L265): 원장 claim 뒤 수신자 설치를 다시 읽어 사라진 세대를 제외하고 claim을 취소하며, 남은 세대의 소유 계정을 `accountId`로 싣는다.
- [수신 측 검증 payload](../../../../cowork-notification/internal/infra/fcm/sender.go#L37): `accountId` data key와 FID별 메시지 전송을 정의한다.

## 할 일

- ~~최초 전송도 저장된 `installation_id` 세대의 현재 소유권을 확인하고 사라진 설치는 제외한다.~~
- ~~소유권 검증 이후 이전과 이미 진행 중인 FCM 요청의 처리·수신 측 계정 검증 정책을 정한다.~~ (수신 측 `accountId` 검증)
- FCM 메시지를 data-only로 전환하고 클라이언트의 `accountId` 검증 뒤 로컬 알림 생성과 함께 배포한다.
- 계정 전환·로그아웃 등록 해제와 `accountId` 불일치 메시지 무시의 클라이언트 적용 상태를 확인한다.

## 검증

- 최초 전송 후보 선택에서 이전·해제된 설치를 제외하고 소유 계정을 `accountId`로 싣는 업무 규칙을 서비스 단위 테스트로 확인한다.
- 설치 이전·등록 해제와 최초 전송·재시도가 겹칠 때 확정한 회수 경계를 수동 확인한다.

## 완료 조건

- FID 하나는 최대 한 계정에 연결되어 있다.
- 최초 전송과 재시도는 현재 소유권 검증을 통과한 설치만 대상으로 삼는다.
- 진행 중인 외부 전송의 회수 한계와 수신 측 계정 검증 정책이 명시되어 있다.
- 백그라운드 수신에서도 `accountId`가 현재 계정과 다른 메시지는 표시되지 않는다.
- migration과 클라이언트 전환 확인 결과가 기록되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#413](https://github.com/team-cowork/cowork-server/pull/413) · [#447](https://github.com/team-cowork/cowork-server/pull/447).
- [대조 코드](../../../../cowork-notification/internal/infra/fcm/sender.go): 단일 소유권과 전송 직전 세대 확인·accountId는 반영되었지만 Message.Notification이 남아 있다. PR #447 리뷰에서도 data-only 동시 전환을 후속으로 남겼다.
- 판정: **부분 구현**. data-only·클라이언트 표시 정책의 동시 전환과 migration 적용을 남은 범위로 유지한다.
