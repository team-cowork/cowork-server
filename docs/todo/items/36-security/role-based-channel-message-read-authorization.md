# 역할 기반 채널·메시지 읽기 권한 적용

- **서비스**: cowork-preference, cowork-team, cowork-channel, cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: 1차 구현 및 회귀 검증 완료 — 채널 삭제 시 authoritative 정책 정리와 운영 전환 절차는 후속 보강 필요
- **관련 작업**: [Preference 리소스별 권한 검증](../15-security/preference-resource-authorization.md), [멤버십 회수 시 WebSocket 구독 강제 해제](../25-security/websocket-membership-revocation.md)
- **파생 원본**: [통합 검색의 비공개 채널 노출 차단](../24-security/private-channel-search-visibility.md)

> **2026-08-30 정책 구상:** 역할과 채널의 다대다 바인딩마다 `{"message_read": false}` 같은 권한 JSON을 저장하고,
> 한 사용자가 가진 여러 역할은 역할 priority 순으로 평가하는 리소스 단위 IAM 모델을 적용한다.
>
> **2026-08-30 평가 규칙 확정:** 기존 `TeamRoleDefinition.priority`의 큰 숫자를 상위로 사용한다. 해당 역할과 채널의 정책
> 바인딩 자체가 없으면 다음 낮은 priority로 상속한다. 명시적으로 제출한 바인딩에서 알려진 키가 빠지면 키별 기본값을
> 채우며, 현재 `message_read` 기본값은 `false`다. 같은 priority의 값이 충돌하면 `false`를 적용하고 끝까지 바인딩이 없으면
> 거부한다. built-in `OWNER`만 허용하되 비공개 채널의 활성 멤버십 조건은 우회하지 않는다.

## 문제

구현 전 `cowork-preference`는 사용자 정의 팀 역할의 `permissions`를 문자열 배열로 PostgreSQL에 저장하고
`preference.team-role.changed`로 역할 정의와 계정별 할당 상태를 발행한다. 그러나 허용하는 권한 이름의 목록과 기본 역할
정책은 정의되어 있지 않으며, production 코드에서 해석하는 값도 `MANAGE_ROLES` 하나뿐이다. `cowork-channel`과
`cowork-chat`은 이 토픽을 소비하지 않아 채널·메시지 읽기 경로에서는 역할 이름과 권한 값을 모두 무시한다.

일반 리소스 설정은 역할 권한 저장소를 대신할 수 없다. `SettingSchema`의 `TEXT_CHANNEL` 허용 키는 `webhook`뿐이고
`PROJECT` 허용 키는 비어 있으므로 `read_messages`나 `allowed_roles` 같은 값을 넣어도 저장 전에 제거된다. 프로젝트 역할의
`permissions`는 임의 JSON 객체로 저장되지만 이를 검증·전파·평가하는 코드가 없다. 별도 비즈니스 설정 JSON이나 초기 역할
seed도 없으며 기존 팀 역할의 기본 `permissions` 값은 빈 배열이다.

따라서 역할 정의의 팀 전역 문자열 권한이 아니라 역할과 채널을 연결하는 별도 정책 aggregate가 필요하다. 정책 바인딩의
부재와 명시적으로 제출한 JSON의 키 누락을 구분하지 않으면 하위 역할 상속과 기본 거부의 의미가 섞인다. 바인딩이 없을 때만
다음 낮은 priority를 평가하고, 제출된 바인딩에서 알려진 키가 빠지면 키별 기본값으로 정규화한다. 반대로 기존 멤버십 검사만
유지하면 `message_read=false`인 역할도 채널 메타데이터, 저장 메시지, 실시간 메시지와 알림 본문을 계속 받을 수 있다. 로컬
projection이 준비된 뒤 모든 읽기 경로에 같은 effective policy 판정을 적용한다.

## 정책 결정

| 항목 | 현재 표현 가능 범위 | 결정할 계약 |
|------|---------------------|-------------|
| 권한 어휘 | 검증 없는 문자열 집합 | 알려진 키는 키별 타입을 검증하고 알 수 없는 키는 입력을 허용하되 저장·이벤트에서 제거한다. 현재 boolean `message_read`만 해석한다 |
| 권한 범위 | 팀 전체 역할 권한만 존재 | `role × channel` 다대다 바인딩마다 허용·거부 JSON을 저장한다 |
| 비공개 채널 | 활성 채널 멤버십 | 활성 채널 멤버십과 effective `message_read`를 모두 요구한다 |
| 다중 역할 | 계정에 여러 역할 할당 가능 | 각 키를 role priority 내림차순으로 탐색하고 가장 높은 명시 값으로 결정한다 |
| 동일 priority | 같은 priority 값을 허용함 | 같은 단계에서 `true`와 `false`가 충돌하면 `false`를 적용한다 |
| 정책 바인딩 부재 | 역할에 해당 채널 정책이 없음 | 다음 낮은 priority로 상속하고 끝까지 바인딩이 없으면 거부한다 |
| 알려진 키 누락 | 제출한 바인딩에 키가 없음 | 키별 기본값으로 채운다. 현재 `message_read` 기본값은 `false`다 |
| 멱등성 식별 | 입력 JSON 표현이 다를 수 있음 | 알 수 없는 키 제거와 기본값 보충을 마친 canonical 권한으로 request hash를 계산한다 |
| 기본 역할 | `OWNER`/`ADMIN`/`MEMBER`는 `cowork-team` 소유 | `OWNER`만 허용하고 `ADMIN`과 `MEMBER`는 사용자 정의 역할 정책을 평가한다 |
| 기존 데이터 | 빈 `permissions`가 허용됨 | 정책이 없는 기존 사용자도 기본 거부하며 운영자가 채널별 정책을 명시적으로 구성한다 |
| 프로젝트 채널 | 팀 역할과 별도 프로젝트 역할이 공존함 | 1차에서는 사용자 정의 팀 역할만 평가하며 프로젝트 역할 연동은 별도 계약으로 미룬다 |

채널 멤버 projection의 `role`은 producer가 현재 `MEMBER`로만 발행하는 값이므로 사용자 정의 팀 역할로 해석하지 않는다.
프로젝트 역할의 임의 JSON도 계약과 projection을 갖추기 전까지 읽기 인가의 근거로 사용하지 않는다.

## 2026-08-30 구현 현황

- `TeamRoleDefinition.priority`의 큰 숫자를 상위로 사용하는 평가기를 `cowork-channel`과 `cowork-chat`에 추가했다.
- `role × channel`별 `message_read` boolean full state와 삭제 tombstone을 `cowork-preference`가 소유하고 compacted
  state event로 발행한다.
- 정책 입력의 알 수 없는 키는 제거하고 알려진 키는 키별 타입을 검증하며, 누락된 `message_read`는 `false`로 채운 canonical
  권한만 저장하고 state event로 발행한다.
- 같은 priority의 충돌은 deny 우선, 상위 역할에 해당 정책 바인딩이 없으면 다음 낮은 priority로 상속, 끝까지 바인딩이
  없으면 deny로 구현했다.
- 공개 채널 메타데이터는 팀 멤버십과 정책, 비공개 채널 메타데이터는 활성 채널 멤버십과 정책을 모두 요구한다.
- 메시지 본문·검색·WebSocket·pin·file·unread·알림은 공개 여부와 관계없이 기존 활성 채널 멤버십과 정책을 모두
  요구한다.
- built-in `OWNER`는 role 정책만 우회하며 메시지 본문과 비공개 채널의 채널 멤버십 조건은 우회하지 않는다.
- 비동기 정책 변경 API와 operation 조회, command inbox/outbox, projection readiness fail-closed를 추가했다. 정책 변경의
  request hash는 canonical 권한을 사용하므로 무시되는 키나 키 누락 표현이 멱등성 식별자를 바꾸지 않는다.
- 역할·할당 삭제 tombstone과 멤버 탈퇴 fence를 영구 보존하고, DB에서 단조 증가하는 버전으로 full snapshot에
  재발행해 토픽 generation 교체 뒤 오래된 allow가 되살아나지 않도록 했다.
- 정책 command-result의 계약 오류는 원본 payload를 보존한 DLT로 격리하고, 일시 오류는 유한 재시도 뒤 DLT로
  전송해 단일 레코드가 consumer partition을 영구 정지시키지 않도록 했다.
- 채널 삭제 후 `cowork-preference`의 authoritative 정책을 정리하는 수명주기 이벤트와 실제 운영 데이터 전환 절차는
  아직 남아 있어 이 TODO는 열린 상태로 유지한다.

## 할 일

### 권한 원천과 전파

- ~~`cowork-preference`에 역할·채널 다대다 정책 테이블과 JSON object 형태 검증을 추가한다.~~
- ~~알려진 권한 키의 타입 검증과 키별 기본값 보충을 추가하고 알 수 없는 키를 저장·이벤트에서 제거한다.~~
- built-in 역할의 기본값과 기존 사용자 정의 역할의 backfill을 migration 또는 명시적인 전환 작업으로 적용한다.
- ~~역할·채널·허용 또는 거부 상태를 표현하는 compacted state event와 삭제 tombstone을 추가한다.~~
- ~~`cowork-channel`과 `cowork-chat`이 역할 정의·할당·삭제 tombstone·snapshot completion을 로컬 projection으로 소비한다.~~
- ~~projection이 불완전하거나 readiness가 닫힌 상태에서는 권한이 필요한 요청을 `503`으로 fail-closed한다.~~
- 채널 삭제 event를 `cowork-preference`의 authoritative 정책 tombstone 정리와 queued command fence에 연결한다.

### 공통 읽기 인가

- ~~비공개 채널에는 활성 채널 멤버십과 effective `message_read`를 함께 검사한다.~~
- ~~effective `message_read=false`인 채널은 검색·팀 목록·프로젝트 목록·단건 조회와 채널 멤버 조회에서 메타데이터도 숨긴다.~~
- ~~메시지 검색·목록·thread·pin·file·unread·알림 대상 계산에 동일한 `message_read` 판정을 적용한다.~~
- ~~WebSocket 채널 join과 실시간 메시지 전달에 같은 판정을 적용하고 권한 회수 시 기존 room 구독을 제거한다.~~
- ~~비공개 채널 생성·수정 이벤트를 팀 전체 room에 보내지 않고 현재 가시성이 있는 사용자에게만 전달한다.~~
- ~~역할 또는 권한 변경 직후 검색 index나 캐시의 오래된 값으로 접근이 되살아나지 않도록 projection version을 비교한다.~~

### 설정 API 보호

- ~~역할별 채널 설정 API에서 요청자의 리소스 관리 권한을 먼저 검증한다.~~
- ~~요청마다 다른 서비스에 내부 HTTP를 호출하지 않고 도메인 이벤트 projection으로 팀·채널 상태를 동기화한다.~~
- ~~권한 변경과 state event outbox 적재를 하나의 PostgreSQL transaction으로 처리한다.~~

## 검증

- 알 수 없는 권한 키가 입력되어도 요청은 수용하되 해당 키가 저장되거나 이벤트로 발행되지 않는지 검증한다.
- 알려진 권한 키의 타입이 잘못되면 요청이 거부되고, 누락된 `message_read`는 `false`로 저장·발행되는지 검증한다.
- 정책 바인딩 부재는 하위 역할로 상속되지만 명시적으로 제출한 빈 바인딩은 `message_read=false`로 평가되는지 검증한다.
- 알 수 없는 키 포함 여부처럼 원문만 다른 동일한 정책 입력이 같은 canonical request hash와 멱등 결과를 갖는지 검증한다.
- built-in 역할, 역할이 없는 멤버, 여러 사용자 정의 역할 조합의 effective permission을 표 기반 테스트로 검증한다.
- 공개·비공개·프로젝트 채널 각각에서 허용·거부·멤버십 회수 조합을 검증한다.
- 읽기 권한이 없는 사용자가 채널 ID·이름·설명, 메시지 본문, pin·file·unread·알림 본문을 받지 못하는지 검증한다.
- 권한 회수 전에 연결한 WebSocket도 회수 후 실시간 메시지와 채널 이벤트를 받지 못하는지 검증한다.
- 역할 정의·할당 tombstone과 snapshot replay 뒤에도 동일한 판정이 유지되는지 검증한다.
- 기존 빈 권한 데이터의 전환 전후에 의도하지 않은 전체 차단이나 우회 허용이 없는지 검증한다.

## 완료 조건

- 허용 권한 이름, 키별 타입·기본값, 알 수 없는 키 제거, 합성 규칙, built-in 기본값과 기존 데이터 전환 정책이 코드와 계약
  문서에 일치한다.
- `cowork-channel`과 `cowork-chat`은 준비된 로컬 projection만으로 effective permission을 판정한다.
- 읽기 권한이 없는 역할은 모든 HTTP·검색·WebSocket·알림 경로에서 채널 메타데이터와 메시지 본문을 받지 않는다.
- 비공개 채널은 정책에서 정한 멤버십과 역할 권한 조건을 모두 충족한 사용자에게만 노출된다.
- 권한 삭제·할당 회수·팀 또는 채널 삭제가 적용된 뒤 기존 연결과 캐시를 통해 접근이 되살아나지 않는다.
