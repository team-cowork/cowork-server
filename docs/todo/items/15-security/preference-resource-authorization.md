# Preference 리소스별 권한 검증

- **서비스**: cowork-preference, cowork-gateway
- **우선순위**: 🔴 높음
- **현재 상태**: Gateway 인증 뒤 공개되는 Preference API가 요청자 헤더를 읽지 않고 경로의 리소스 ID만으로 조회·수정을 수행함

## 문제

`cowork-gateway`는 `/api/preference/preferences/**` 요청을 인증한 뒤 `X-User-Id`와 `X-User-Role`을 설정해 `cowork-preference`로 전달한다. 그러나 `PreferenceHandler`, `NotificationHandler`, `ProjectRoleHandler`는 두 헤더를 읽지 않으며, 요청 경로와 본문의 `accountId`, `teamId`, `projectId`, 채널 ID만 서비스에 전달한다.

따라서 인증된 사용자는 경로 ID를 바꾸는 것만으로 다른 계정의 설정과 알림 수신 여부를 조회·수정할 수 있다. 팀·프로젝트·채널 설정과 프로젝트 역할 생성·삭제·할당도 요청자의 소속이나 관리 권한을 확인하지 않는다. Gateway 인증은 호출자의 신원만 증명할 뿐 리소스 접근 권한을 대신하지 않으므로, 현재 공개 라우트에는 수평 권한 상승 경로가 남아 있다.

`cowork-preference`에는 팀 멤버 projection이 일부 존재하지만 공개 HTTP handler의 권한 검증에는 사용되지 않는다. 프로젝트·채널 범위의 판단 자료가 부족하다면 도메인 이벤트 projection을 보강해야 하며, 요청마다 다른 서비스에 내부 HTTP를 호출하는 방식은 사용하지 않는다.

## 권한 정책

| API 범위 | 최소 조회 권한 | 최소 수정 권한 |
|----------|----------------|----------------|
| `/preferences/account/{id}` | 본인 또는 명시된 운영자 | 본인 또는 명시된 운영자 |
| `/preferences/account/{accountId}/channels/{channelId}/notification` | 본인과 유효한 채널 멤버십 | 본인과 유효한 채널 멤버십 |
| `/preferences/team/{id}` | 팀 멤버 | 팀 설정 관리 권한 |
| `/preferences/project/{id}` | 프로젝트가 속한 팀의 멤버 | 프로젝트 설정 관리 권한 |
| `/preferences/voice-channel/{id}`, `/preferences/text-channel/{id}` | 채널 멤버 | 채널 설정 관리 권한 |
| `/preferences/project/{projectId}/roles/**` | 프로젝트 멤버 | 프로젝트 역할 관리 권한 |

세부 역할 이름과 ADMIN 예외 범위는 `cowork-team`, `cowork-project`, `cowork-channel`의 현재 권한 계약과 맞춰 확정한다.

## 할 일

### 요청자 컨텍스트

- `X-User-Id`와 `X-User-Role`을 검증해 공통 요청자 컨텍스트로 변환한다.
- 공개 라우트에서 요청자 헤더가 없거나 형식이 잘못된 요청을 거부한다.
- handler뿐 아니라 서비스 메서드에도 요청자 컨텍스트를 전달해 내부 호출에서 검증을 우회하지 못하게 한다.

### 리소스 권한

- 계정 설정과 알림 설정에 본인 일치 검증을 적용한다.
- 팀·프로젝트·채널 설정과 역할 API에 멤버십 및 관리 권한 guard를 적용한다.
- 필요한 프로젝트·채널 멤버십을 Kafka projection으로 동기화하고 삭제·권한 회수 이벤트를 반영한다.
- 권한 거부 응답을 `403`, 존재하지 않는 리소스를 `404`로 일관되게 매핑한다.
- 타인 리소스 수정 시도를 민감한 설정값 없이 감사 로그로 남긴다.

## 검증

- 계정 A가 계정 B의 설정과 알림 설정을 조회·수정하지 못하는 규칙을 권한 서비스 단위 테스트로 검증한다.
- 일반 멤버, 관리 권한 보유자, 전역 ADMIN의 허용·거부 범위를 guard와 서비스 단위 테스트로 검증한다.
- 요청자 컨텍스트 누락·형식 오류와 `403`·`404` 판단을 외부 의존성 없는 단위 테스트로 검증한다.
- 공개 Preference 경로 전체에 요청자 컨텍스트 전달이 적용됐는지는 라우트 선언과 호출 그래프를 정적으로 점검한다.
- 멤버십 삭제 이벤트의 전달·projection 반영은 운영 지표와 데이터 점검 대상으로 두며 Kafka 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- Preference 공개 API는 호출자의 리소스별 권한을 확인한 뒤에만 데이터를 조회·변경한다.
- 경로 ID를 바꿔 다른 계정·팀·프로젝트·채널의 설정이나 역할을 조작할 수 없다.
- 권한 판단에 필요한 projection이 삭제·회수 이벤트까지 반영한다.
- 핵심 허용·거부 시나리오가 권한 서비스와 guard 단위 테스트로 자동화되어 있다.
