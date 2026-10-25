# FCM 푸시 페이로드와 클라이언트 계약

서버는 전송 직전에 Firebase 설치(FID)의 소유 계정을 다시 확인하지만, FCM에 넘긴 메시지는 회수할 수 없습니다. 확인 직후 계정이 바뀐 기기에도 이전 계정의 메시지가 도착할 수 있으므로, 표시 여부는 클라이언트가 최종 판단합니다.

## data 키

| 키          | 값                                       |
|-------------|------------------------------------------|
| `accountId` | 메시지를 받을 설치 세대의 소유 계정 ID   |
| `title`     | 알림 제목. 전송 모드와 관계없이 항상 포함 |
| `body`      | 알림 본문. 전송 모드와 관계없이 항상 포함 |

## 전송 모드

Config Server의 `fcm.data-only`(환경변수 `FCM_DATA_ONLY`가 우선)로 정합니다. 기본값은 `false`입니다.

- `false`(호환): `notification`과 `data`를 함께 보냅니다. 앱이 백그라운드이면 OS가 계정 확인 전에 알림을 표시하므로 계정 불일치 알림을 막지 못합니다.
- `true`(data-only): `notification` 없이 `data`만 보냅니다. 클라이언트가 `accountId`를 확인한 뒤 `title`·`body`로 로컬 알림을 만듭니다. 이를 처리하지 않는 클라이언트는 백그라운드에서 아무것도 표시하지 않으므로, 아래 계약을 구현한 클라이언트를 배포한 뒤에 켭니다.

## 클라이언트 계약

- 로그아웃 상태이거나 `accountId`가 현재 로그인 계정과 다르면 알림을 만들지 않고 메시지를 버립니다.
- 로그아웃·계정 전환 시 `DELETE /notifications/installations/{fid}`로 기존 계정의 설치를 해제한 뒤 새 계정으로 다시 등록합니다. 해제 전에 이미 FCM에 넘어간 메시지는 위 검사로 걸러냅니다.

## 플랫폼별 동작(data-only)

- Android: 우선순위 `high`로 보내 Doze 상태에서도 `onMessageReceived`가 호출되게 합니다. 표시하지 않고 버리는 high 메시지가 많으면 FCM이 해당 앱의 우선순위를 낮출 수 있습니다. 사용자가 앱을 강제 종료하면 다시 실행할 때까지 받지 못합니다.
- iOS: `content-available: 1`, `apns-push-type: background`, `apns-priority: 5`로 보냅니다. 시스템이 백그라운드 푸시 빈도를 제한하고 강제 종료된 앱에는 전달하지 않으므로 알림이 늦거나 누락될 수 있습니다. 누락 없이 거르려면 알림 푸시를 Notification Service Extension에서 거르는 방식(`com.apple.developer.usernotifications.filtering` 권한 필요)이 필요하며, 서버는 아직 이 방식을 지원하지 않습니다.
- Web: 서비스 워커의 `push`(또는 `onBackgroundMessage`) 처리기가 계정을 확인하고 직접 알림을 표시합니다.
