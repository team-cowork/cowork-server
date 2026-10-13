# 기존 팀의 채널 역할 정책 전환

현재 운영은 새 데이터셋을 사용한다. 이 절차는 기존 팀 데이터를 유지하는 환경의 정책 전환에만 적용한다.
기존 역할 문자열에서 메시지 읽기 권한을 추론하지 않는다. 정책 없는 non-`OWNER`를 기본 거부할 팀도
운영 결정을 남긴다. 빈 snapshot의 완료만으로 정책 전환 완료를 판단하지 않는다.

도구는 [`scripts/channel_role_policy_transition.py`](../scripts/channel_role_policy_transition.py)다.
명령 인자·manifest 형식·재시도 동작은 도구의 parser와 검증을 기준으로 한다.

## 운영자가 준비하는 export

쓰기 트래픽을 차단한 뒤 각 소유 DB에서 읽기 전용으로 추출한다. 서로 다른 DB의 일관된 시점을
도구가 보장하지 않으므로 동작 중인 데이터셋을 임의로 조합하지 않는다. CSV·TSV 첫 행에 header를 둔다.

| 파일                   | DB                           | 쿼리                                                                                                              |
|------------------------|------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `team-members.csv`     | cowork-team MySQL            | `SELECT team_id, user_id, role FROM tb_team_members`                                                              |
| `roles.csv`            | cowork-preference PostgreSQL | `SELECT id, team_id, priority FROM tb_team_role_definitions`                                                      |
| `role-assignments.csv` | cowork-preference PostgreSQL | `SELECT team_id, account_id, role_id FROM tb_account_team_roles`                                                  |
| `policies.csv`         | cowork-preference PostgreSQL | `SELECT team_id, channel_id, role_id, permissions->>'message_read' AS message_read FROM tb_channel_role_policies` |
| `channels.csv`         | cowork-channel MySQL         | `SELECT id, team_id, is_private + 0 AS is_private FROM tb_channels WHERE team_id IS NOT NULL`                     |
| `channel-members.csv`  | cowork-channel MySQL         | `SELECT channel_id, user_id FROM tb_channel_members`                                                              |


검증용 `channel-projection.csv`는 `tb_channel_role_policy_projections`의 삭제되지 않은 행을,
`chat-projection.csv`는 MongoDB `channelrolepolicyprojections`의 삭제되지 않은 문서를 추출한다.
두 파일의 열은 `teamId,channelId,roleId,messageRead`다.

## 적용 경계

- `plan`의 경고는 팀별로 검토한다. 승인한 manifest와 변경 전 export를 보관한다.
- `apply`는 Gateway 신뢰 헤더를 사용하므로 허용된 사설 관리 경로에서만 실행한다.
- authoritative 정책과 두 projection이 수렴하고 실제 역할별 읽기 결과를 확인한 뒤 트래픽을 연다.
- rollback은 변경 전 export로 생성한 새 manifest를 적용한다. DB 직접 변경으로 outbox를 우회하지 않는다.
- 운영 manifest·export에는 실제 사용자 데이터가 있으므로 저장소에 커밋하지 않는다.
