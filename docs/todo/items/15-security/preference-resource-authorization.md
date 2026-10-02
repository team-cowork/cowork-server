# Preference 리소스별 권한 검증

- **서비스**: cowork-preference, cowork-gateway
- **우선순위**: 🔴 높음
- **현재 상태**: 공개 Preference handler가 호출자 헤더를 읽지 않고 리소스 ID로 조회·수정한다.

## 문제

`PreferenceHandler`, `NotificationHandler`, `ProjectRoleHandler`는 Gateway가 전달한 호출자 신원을
권한 판단에 사용하지 않는다. 인증된 사용자가 경로·본문 ID를 바꿔 타인의 설정·알림이나 다른
리소스의 역할을 조작할 수 있다.

리소스별 권한에 필요한 멤버십이 부족하면 로컬 Kafka projection을 보강한다.
요청마다 다른 서비스에 내부 HTTP로 조회하는 경로는 추가하지 않는다.

## 권한 정책

| 범위 | 조회 | 수정 |
|---|---|---|
| 계정 설정 | 본인 (운영 예외는 미확정) | 본인 (운영 예외는 미확정) |
| 계정별 채널 알림 | 본인과 유효한 채널 멤버십 | 동일 |
| 팀·프로젝트 설정 | 해당 범위의 멤버 | 설정 관리 권한 |
| 채널 설정 | 채널 멤버 | 채널 설정 관리 권한 |
| 프로젝트 역할 | 프로젝트 멤버 | 프로젝트 역할 관리 권한 |

운영 예외의 허용 여부, 주체(전역 `ADMIN` 또는 별도 운영자), 조회·수정 범위는 계정 소유 서비스의
계약에 맞춰 확정한다. 미확정 예외를 접근 허용 근거로 사용하지 않는다.
팀·프로젝트·채널의 세부 역할은 각 소유 서비스의 계약에 맞춰 확정한다.

## 코드 근거

- [공개 라우터](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/router/PreferenceRouter.kt#L57): 본문 parser 뒤 공개 리소스 handler를 직접 연결하며 요청자 인가 middleware가 없다.
- [설정 handler](../../../../cowork-preference/src/main/kotlin/com/cowork/preference/handler/PreferenceHandler.kt#L58): resource ID·body만 service에 전달하고 호출자 신원을 전달하지 않는다.
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
