# 기존 역할·채널 정책 운영 환경 전환 수행

- **서비스**: cowork-preference, cowork-team, cowork-channel, cowork-chat, 배포 운영
- **우선순위**: 🔴 높음
- **현재 상태**: 정책 평가와 전환 도구·절차는 구현되어 있으며 실제 운영 manifest·적용·검증 결과는 저장소에서 확인할 수 없다.

## 문제

현재 읽기 인가는 항상 채널 역할 정책을 평가한다. 정책이 없는 팀의 non-`OWNER` 기본 거부를
유지할지 적용할 정책을 만들지 운영 결정이 필요하다. 실제 배포 버전과 영향을 받는 팀·사용자는
아직 확인하지 않았다.

도구와 문서가 준비된 사실만으로 기존 팀의 전환이 끝나지 않는다.
승인 manifest·적용·projection 수렴과 표본 읽기의 운영 결과를 남긴다.

## 코드 근거

- [Channel 정책 평가](../../../../cowork-channel/src/main/kotlin/com/cowork/channel/domain/channelRolePolicy/service/ChannelMessageReadPolicyEvaluator.kt#L24): OWNER 예외와 역할별 우선순위·deny 평가를 항상 수행한다.
- [Chat 정책 평가](../../../../cowork-chat/src/chat/service/channel-message-read-access.service.ts#L338): 메시지·metadata의 읽기 범위와 역할 정책을 적용한다.
- [운영 전환 도구](../../../../scripts/channel_role_policy_transition.py#L243): plan·apply·verify·rollback-manifest 경로는 있다. 실제 운영 export·승인·적용 성공 기록은 코드로 입증할 수 없다.

## 할 일

- 실제 배포 버전과 정책 없는 팀·사용자를 확인하고 maintenance window를 정한다.
- 실제 export 형식과 정책 API 응답을 수동 확인하고 도구 입력·응답 해석을 맞춘다.
- [운영 절차](../../../channel-role-policy-transition.md)에 따라 팀별 manifest·기본 거부 유지 승인을 준비한다.
- `plan` 결과를 검토한 뒤 `apply`·`verify`와 공개·비공개 표본 읽기를 확인한다.
- 재시작·다음 full snapshot 뒤에도 정책·projection 수렴을 확인한다.
- manifest hash·승인·operation state·검증 결과를 저장소 밖 운영 기록에 남긴다.

## 검증

- `plan`이 읽기 전용이고 같은 manifest 재실행이 기존 operation을 사용하는지 수동 확인한다.
- operation 성공과 실패 사유를 대조한다. 승인한 실패가 필요한 정책 미적용을 숨기지 않는지 확인한다.
- 소유자 정책과 channel·chat projection 전체의 키·값, 역할별 실제 읽기를 확인한다.

## 완료 조건

- 기존 팀마다 승인 정책이 적용되거나 기본 거부 유지가 승인되어 있다.
- 소유자 정책과 두 projection이 일치하며 의도하지 않은 전체 차단·우회 허용이 없다.
- 적용·수렴·승인 결과가 환경별 운영 기록에 남아 있다.
