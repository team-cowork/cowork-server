# Preference 리소스별 권한 검증

- **서비스**: cowork-preference, cowork-gateway
- **우선순위**: 🔴 높음
- **현재 상태**: 계정 설정·계정별 채널 알림은 본인 여부를, 팀 설정은 멤버십·관리 권한을 확인하며 알림의 채널 멤버십과 프로젝트·채널 범위 인가는 남아 있다.

> **진척:** `RequesterContext`(X-User-Id/X-User-Role 파싱)와 기존 `tb_team_member_projections`를 재사용하는
> `TeamMembershipGuard`를 추가해 `/preferences/team/{id}` GET·PUT에 팀 멤버십·설정 관리 권한 검증을 적용했다.
> 전역 `ADMIN`은 우회하고, projection 미준비는 `503`, 비멤버·권한 부족은 `403`으로 응답한다.
>
> **진척:** `/preferences/account/{id}` GET·PUT은 `RequesterContext`로 요청자를 확인한 뒤
> `AccountOwnershipGuard`로 본인 여부를 검증한다. 전역 `ADMIN`도 타인 계정에는 접근할 수 없고
> `403`을 반환한다. 미확정 운영 예외는 허용하지 않는다. 헤더 누락·오류는 `400`으로 응답한다.
> 소유자·요청자·기존 팀 권한 단위 테스트 23개를 통과했으며 공개 계정 경로의 guard 호출과
> `OpenAPI`의 `403` 응답을 정적으로 확인했다. 프로젝트·채널 projection과 운영 전환은 아직 검증하지 않았다.
>
> **진척:** `/preferences/account/{accountId}/channels/{channelId}/notification` GET·PUT도 `RequesterContext`와
> `AccountOwnershipGuard`로 본인 여부를 검증한다. 헤더 누락·오류는 `400`, 타인 계정은 `403`이며 `OpenAPI`에 `403`을 반영했다.
> 권한 표의 채널 멤버십 조건은 채널 멤버 projection이 필요해 아래 후속 작업에 포함한다.
>
> **결론:** project/channel(voice-channel, text-channel, notification의 채널 멤버십, project-role) 범위는 아직
> 처리하지 않았다 — `cowork-preference`가 `project.member.event.v2`/`channel.member.event.v2`를 소비하지
> 않아 이 두 projection을 team-member projection과 동일한 패턴(신규 Kafka consumer, 신규 마이그레이션)으로
> 새로 만들어야 하며, 이는 별도 후속 작업으로 남겨둔다.

## 문제

`PreferenceHandler`의 계정·팀 경로와 `NotificationHandler`의 계정 소유권은 Gateway가 전달한 호출자 신원을 권한 판단에 사용한다.
프로젝트·채널 설정과 `ProjectRoleHandler`에는 아직 같은 검증이 없고 알림은 채널 멤버십을 확인하지 않아,
인증된 사용자가 경로·본문 ID를 바꿔 다른 리소스의 설정이나 역할을 조작할 수 있다.

리소스별 권한에 필요한 멤버십이 부족하면 로컬 Kafka projection을 보강한다.
요청마다 다른 서비스에 내부 HTTP로 조회하는 경로는 추가하지 않는다.

## 권한 정책

| 범위 | 조회 | 수정 |
|---|---|---|
| 계정 설정 | 본인 (미확정 운영 예외는 거부) | 본인 (미확정 운영 예외는 거부) |
| 계정별 채널 알림 | 본인과 유효한 채널 멤버십 | 동일 |
| 팀·프로젝트 설정 | 해당 범위의 멤버 | 설정 관리 권한 |
| 채널 설정 | 채널 멤버 | 채널 설정 관리 권한 |
| 프로젝트 역할 | 프로젝트 멤버 | 프로젝트 역할 관리 권한 |

운영 예외의 허용 여부, 주체(전역 `ADMIN` 또는 별도 운영자), 조회·수정 범위는 계정 소유 서비스의
계약에 맞춰 확정한다. 미확정 예외를 접근 허용 근거로 사용하지 않는다.
팀·프로젝트·채널의 세부 역할은 각 소유 서비스의 계약에 맞춰 확정한다.

## 코드 근거

- [공개 라우터](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/router/PreferenceRouter.kt#L57): 본문 parser 뒤 공개 리소스 handler를 직접 연결하며 요청자 인가 middleware가 없다.
- [설정 handler](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/handler/PreferenceHandler.kt): 계정·팀 경로는 guard를 먼저 호출하며 프로젝트·채널 경로는 아직 resource ID·body만 service에 전달한다.
- [계정 소유자 guard](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/service/AccountOwnershipGuard.kt): 조회·수정에 같은 본인 일치 정책을 적용하며 전역 `ADMIN` 우회는 허용하지 않는다.
- [알림 handler](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/handler/NotificationHandler.kt): 계정 소유권만 확인하며 채널 멤버십은 확인하지 않는다.
- [프로젝트 역할 handler](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/handler/ProjectRoleHandler.kt#L113): 역할 생성·할당도 경로·본문 ID만 사용하므로 Gateway 인증으로 리소스 권한이 보완되지 않는다.

## 할 일

- 계정 설정 운영 예외의 허용 여부·주체·조회 및 수정 범위를 확정하고 권한 표와 guard 계약에 반영한다.
- `X-User-Id`·`X-User-Role`을 검증한 요청자 컨텍스트를 handler와 service에 전달한다.
- 계정 일치·멤버십·관리 권한 guard와 필요한 projection을 적용한다.
- projection 미준비·권한 없음·리소스 없음의 응답을 구분하고 시크릿 없는 감사 로그를 남긴다.

## 검증

- 타인 설정 접근, 일반 멤버·관리자·운영 예외의 허용·거부를 권한 서비스 단위 테스트로 확인한다.
- 공개 라우트의 요청자 전달과 guard 호출은 정적으로 점검한다.
- 삭제·권한 회수 projection 반영은 운영 지표·데이터로 확인한다.

## 완료 조건

- 모든 공개 Preference API가 호출자의 리소스별 권한을 확인한다.
- 리소스 ID 변조로 타인의 설정·역할을 읽거나 변경할 수 없다.
- 핵심 허용·거부 정책이 단위 테스트로 보호되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#428](https://github.com/team-cowork/cowork-server/pull/428) · [#456](https://github.com/team-cowork/cowork-server/pull/456).
- [대조 코드](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/handler/PreferenceHandler.kt): ACCOUNT·TEAM GET/PUT만 요청자·guard를 사용한다. 프로젝트·채널과 NotificationHandler·ProjectRoleHandler에는 같은 인가가 없다.
- 판정: **부분 구현**. 프로젝트·채널 projection 인가와 notification의 계정 소유권 검증을 남은 범위로 유지한다.
