# 기존 역할·채널 정책 운영 환경 전환 수행

- **서비스**: cowork-preference, cowork-team, cowork-channel, cowork-chat, 배포 운영
- **우선순위**: 🔴 높음
- **현재 상태**: 전환 도구와 운영 절차는 마련되어 있으나 운영 환경의 manifest 작성·적용·검증은 수행하지 않았다
- **파생 원본**: [기존 역할·채널 정책의 운영 전환](../38-security/existing-channel-role-policy-transition.md)

## 문제

`cowork-channel`의 `ChannelMessageReadPolicyEvaluator`와 `cowork-chat`의 `ChannelMessageReadAccessService`는 설정
토글 없이 항상 채널 역할 정책을 평가한다. 이 평가를 추가한 커밋 `225161a01`은 `main`에 포함되어 있지만 운영 환경에
배포되었는지는 아직 확인하지 않았다. 배포되어 있다면 정책이 없는 팀의 `ADMIN`·`MEMBER`는 지금도 채널 메시지를 읽지
못한다.

전환 도구 `scripts/channel_role_policy_transition.py`와 절차 `docs/channel-role-policy-transition.md`는 준비되어 있다.
도구의 평가 규칙, 사전 점검, operation 계산은 단위 테스트로, `plan`·`apply`·`verify`·`rollback-manifest` 흐름은 가짜
HTTP 서버로만 확인했다. 실제 MySQL·PostgreSQL·MongoDB export 형식과 `cowork-channel` 정책 API의 응답 형식을
상대로는 실행하지 않았다.

manifest 승인, 팀별 기본 거부 유지 승인, 적용 결과는 운영 데이터이므로 저장소에서 완료할 수 없다. 운영 환경에서
절차를 수행하고 결과를 기록해야 기존 팀의 전환이 끝난다.

## 할 일

### 사전 확인

- 운영 환경에 `225161a01`을 포함한 버전이 배포되어 있는지 확인한다. 배포되어 있다면 export로 정책이 없는 팀과
  non-`OWNER` 사용자 수를 파악하고 maintenance window를 우선 잡는다.
- 로컬 환경의 실제 서비스를 대상으로 export, `plan`, `apply`, `verify`, `rollback-manifest`를 리허설한다.
- 리허설에서 export CSV 형식과 `CommonApiResponse` 응답의 `data` 해석이 도구와 맞는지 확인하고, 다르면 도구를 고친다.

### 운영 전환

- 운영 export로 팀별 manifest를 작성하고 `plan`의 `WARN`을 팀별로 검토해 승인한다.
- `docs/channel-role-policy-transition.md`의 순서대로 maintenance window에서 `apply`와 `verify`를 수행한다.
- 공개·비공개 채널에서 `OWNER`, 정책 없는 `ADMIN`·`MEMBER`, allow 역할, deny 역할의 실제 읽기를 표본으로
  확인한 뒤 트래픽을 연다.

### 수렴과 기록

- consumer 재시작과 다음 full snapshot 재발행 뒤 projection을 다시 export해 `verify`를 재실행한다.
- manifest와 SHA-256, `plan` 출력, 기본 거부 유지 승인, `apply` state, `verify` 결과를 환경별 운영 기록에 남긴다.

## 검증

- 리허설에서 `plan` 전후의 정책, outbox, operation 행 수가 같은지 확인한다.
- 리허설에서 같은 manifest로 `apply`를 반복해도 authoritative 정책과 outbox 결과가 한 번 적용한 상태와 같은지 확인한다.
- 운영 적용의 모든 operation이 `SUCCEEDED`이거나 승인 사유가 기록된 `FAILED`인지 확인한다.
- snapshot replay와 consumer 재시작 뒤에도 `verify`가 통과하는지 확인한다.

## 완료 조건

- 운영 환경의 기존 각 팀은 승인된 manifest가 적용되어 있거나 기본 거부 유지가 승인되어 있다.
- `cowork-preference` 정책과 `cowork-channel`·`cowork-chat` projection의 키와 값이 일치한다.
- 전환 뒤 의도하지 않은 non-`OWNER` 전체 차단이나 우회 허용이 남아 있지 않다.
- 전환 결과와 승인 내역이 환경별 운영 기록에 남아 있다.
