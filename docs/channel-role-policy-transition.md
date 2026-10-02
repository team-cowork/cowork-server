# 기존 역할·채널 정책 운영 전환

`cowork-channel`과 `cowork-chat`의 메시지 읽기 인가는 설정 토글 없이 항상 채널 역할 정책을 평가한다.
`V20__add_channel_role_policy_command_contract.sql`은 기존 팀의 정책을 채우지 않으며, 정책이 0건이어도
snapshot completion marker가 발행되므로 projection readiness만으로 전환 완료를 판단할 수 없다.
이 문서는 기존 팀에 운영자가 승인한 정책을 적용하는 maintenance window 절차다.
도구는 `scripts/channel_role_policy_transition.py`이며 Python 3 표준 라이브러리만 사용한다.

기존 역할의 `permissions` 문자열에서 `message_read`를 추론하지 않는다. 정책이 없는 non-`OWNER`의
기본 거부를 유지할 팀도 운영 승인이 필요하다. 정책 부재·상속과 명시적 `false`는 다르므로 manifest에서
구분한다. 도구의 읽기 사전 점검은 채널 메타데이터 기준이며, 실제 메시지·검색·WebSocket은 별도로 확인한다.

## 입력

### Export

각 소유 DB에서 읽기 전용으로 아래 결과를 CSV(쉼표 또는 탭 구분, 첫 줄 header)로 저장한다. 파일 이름은
표와 같아야 하며 한 디렉터리에 모은다. 서비스 간 DB join은 사용하지 않고 도구가 식별자로만 연결한다.

| 파일                   | DB                           | 쿼리                                                                                                              |
|------------------------|------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `team-members.csv`     | cowork-team MySQL            | `SELECT team_id, user_id, role FROM tb_team_members`                                                              |
| `roles.csv`            | cowork-preference PostgreSQL | `SELECT id, team_id, priority FROM tb_team_role_definitions`                                                      |
| `role-assignments.csv` | cowork-preference PostgreSQL | `SELECT team_id, account_id, role_id FROM tb_account_team_roles`                                                  |
| `policies.csv`         | cowork-preference PostgreSQL | `SELECT team_id, channel_id, role_id, permissions->>'message_read' AS message_read FROM tb_channel_role_policies` |
| `channels.csv`         | cowork-channel MySQL         | `SELECT id, team_id, is_private + 0 AS is_private FROM tb_channels WHERE team_id IS NOT NULL`                     |
| `channel-members.csv`  | cowork-channel MySQL         | `SELECT channel_id, user_id FROM tb_channel_members`                                                              |

```bash
mysql --batch -e "SELECT team_id, user_id, role FROM tb_team_members" cowork_team > export/team-members.csv
psql "$PREFERENCE_DB_URL" -c "\copy (SELECT id, team_id, priority FROM tb_team_role_definitions) TO 'export/roles.csv' CSV HEADER"
```

DB 이름과 접속 정보는 환경에 맞게 바꾼다. 세 DB를 같은 시점에 고정할 수는 없으므로 export는 쓰기 트래픽을
막은 뒤 받는다.

### Manifest

운영자가 작성하는 `<version>.manifest.json`이다. `version`은 전환 버전이며, 내용을 바꾸면 반드시 새 값으로 바꾼다.

```json
{
  "version": "prod-20261001-1",
  "teams": {
    "10": {
      "actorId": 7,
      "decision": "APPLY",
      "policies": [
        { "channelId": 100, "roleId": 5, "messageRead": true },
        { "channelId": 101, "roleId": 5, "messageRead": false },
        { "channelId": 102, "roleId": 6, "messageRead": null }
      ]
    },
    "11": { "actorId": 9, "decision": "DEFAULT_DENY_APPROVED", "policies": [] }
  }
}
```

- `actorId`: 정책 변경을 제출할 계정이다. export 기준으로 그 팀의 `OWNER`여야 한다.
- `decision`
  - `APPLY`: `policies`를 적용한다.
  - `DEFAULT_DENY_APPROVED`: 정책 없이 non-`OWNER` 기본 거부를 유지하기로 승인한 팀이다. `policies`는 비워 둔다.
- `messageRead`: `true`는 allow, `false`는 deny, `null`은 정책 부재다. 기존 정책이 있으면 삭제한다.
- manifest에 적지 않은 팀·채널·역할 조합은 현재 상태를 유지한다. export에 있는 팀이 manifest에 없으면
  검토하지 않은 전환 누락으로 보고 적용을 거부한다.

## 절차

### 1. 사전 점검

1. 트래픽을 열어 둔 상태에서 export를 한 번 받아 manifest 초안을 만든다.
2. `plan`으로 점검한다. `plan`은 DB, Kafka, operation 상태를 바꾸지 않는다.

   ```bash
   python3 scripts/channel_role_policy_transition.py plan \
     --manifest prod-20261001-1.manifest.json --export-dir export
   ```

   | 출력    | 의미                                                                                                                   |
   |---------|------------------------------------------------------------------------------------------------------------------------|
   | `ERROR` | 전환 누락 팀, 존재하지 않거나 다른 팀의 채널·역할, `OWNER`가 아닌 actor, 중복 항목. 하나라도 있으면 `apply`가 거부한다 |
   | `WARN`  | 전환 뒤 읽을 수 있는 채널이 0개인 non-`OWNER`, 같은 `priority`의 allow/deny 충돌                                       |
   | `OP`    | 현재 정책과 달라서 제출할 변경. 이미 같은 값인 항목은 제출하지 않는다                                                  |

3. 모든 `WARN`을 팀별로 검토한다. 의도한 결과가 아니면 manifest를 고치고 `version`을 올린다. 승인한
   manifest, `plan` 출력, 기본 거부 유지 승인을 운영 기록에 남긴다.

### 2. Maintenance window

1. Gateway의 외부 API·WebSocket 트래픽을 막고 자동 배포·자동 재시작을 멈춘다.
2. 역할 기반 읽기 인가가 포함된 버전을 아직 배포하지 않았다면 이때 배포한다. `cowork-preference`,
   `cowork-channel`, `cowork-chat`의 readiness가 열릴 때까지 기다린다.
   이미 배포되어 non-`OWNER` 읽기가 막힌 상태라면 가능한 한 빨리 window를 잡아 아래 단계를 진행한다.
3. export를 다시 받아 `plan`을 재실행하고 `ERROR`가 없는지 확인한다. 1단계와 결과가 달라졌으면 차이를 검토한다.
4. 사설망에서 `cowork-channel`에 직접 접근할 수 있는 운영 호스트에서 `apply`를 실행한다. 도구는
   `X-User-Id`에 팀 `OWNER`를 넣어 기존 `PUT`/`DELETE /channels/{channelId}/role-policies/{roleId}`를
   호출한다. Gateway 신뢰 헤더를 쓰는 경로이므로 서비스 포트를 외부에 공개하지 않는다.

   ```bash
   python3 scripts/channel_role_policy_transition.py apply \
     --manifest prod-20261001-1.manifest.json --export-dir export \
     --base-url http://<cowork-channel 사설 주소>:8083
   ```

   - 같은 manifest를 재실행하면 기존 operation을 재사용한다. 내용을 바꾸면 새 `version`으로 적용한다.
   - 진행 상태는 `<version>.state.json`에 기록한다. 모든 operation이 `SUCCEEDED` 또는 `FAILED`가 될 때까지
     조회하며, 모두 `SUCCEEDED`일 때만 종료 코드 0을 반환한다.
   - 같은 `version`의 state가 다른 manifest로 만들어졌으면 적용을 거부한다.

5. 적용 뒤 `policies.csv`를 다시 export하고 두 projection도 export한다.

   cowork-channel MySQL → `channel-projection.csv`:

   ```sql
   SELECT team_id AS teamId, channel_id AS channelId, role_id AS roleId, message_read + 0 AS messageRead
   FROM tb_channel_role_policy_projections
   WHERE deleted = FALSE;
   ```

   cowork-chat MongoDB → `chat-projection.csv`:

   ```bash
   mongoexport --uri "$CHAT_MONGODB_URI" --collection channelrolepolicyprojections \
     --query '{"deleted": false}' --type csv --fields teamId,channelId,roleId,messageRead \
     --out chat-projection.csv
   ```

6. `verify`로 manifest 대상이 authoritative 저장소에 반영됐는지, authoritative 정책 전체와 두 projection의
   키·값이 일치하는지 확인한다. projection은 비동기로 수렴하므로 불일치가 있으면 잠시 뒤 export와 `verify`를
   다시 실행한다.

   ```bash
   python3 scripts/channel_role_policy_transition.py verify \
     --manifest prod-20261001-1.manifest.json --export-dir export-after \
     --channel-projection channel-projection.csv --chat-projection chat-projection.csv
   ```

7. 공개·비공개 채널에서 `OWNER`, 정책 없는 `ADMIN`·`MEMBER`, allow 역할, deny 역할의 실제 읽기를 표본으로
   확인한 뒤 트래픽을 연다.

### 3. 운영 기록

환경별 운영 기록에 아래 항목을 남긴다. 저장소에는 실제 운영 데이터를 커밋하지 않는다.

- 승인한 manifest와 SHA-256, `plan` 출력, 팀별 기본 거부 유지 승인
- `apply` 요약과 `<version>.state.json`, 운영자가 승인한 `FAILED` operation과 사유
- `verify` 결과, 표본 읽기 확인 결과, 트래픽 재개 시각

## 부분 실패와 재개

- `apply`가 중단되거나 제한 시간을 넘기면 같은 manifest, export, state로 다시 실행한다. 제출된 operation은
  다시 제출하지 않고 상태만 조회하며, `SUBMIT_ERROR`는 같은 `Idempotency-Key`로 다시 제출한다.
- `503`은 projection catch-up 중이라는 뜻이다. readiness가 열린 뒤 다시 실행한다.
- `FAILED`는 `cowork-preference`가 거부한 결과다(`CHANNEL_DELETED`, `ROLE_NOT_FOUND`,
  `ACTOR_MEMBERSHIP_CHANGED` 등). 같은 key로 재시도해도 결과는 바뀌지 않는다. 원인이 manifest에 있으면
  고친 manifest를 새 `version`으로 적용하고, 의도한 실패면 승인 사유를 기록한다.

## Rollback

전환 직전(2단계 3번)에 받은 export로 manifest가 건드린 조합을 이전 상태로 되돌리는 manifest를 만든다.
그래서 그 export 디렉터리는 전환이 끝난 뒤에도 보관한다. 이전에 정책이
없던 조합은 `null`(삭제), 있던 조합은 이전 값이 된다. 생성된 manifest의 `version`은 `<version>-rollback`이다.

```bash
python3 scripts/channel_role_policy_transition.py rollback-manifest \
  --manifest prod-20261001-1.manifest.json --before-export-dir export --out rollback.manifest.json
```

rollback manifest도 같은 `plan` → `apply` → `verify` 절차로 적용한다. DB를 직접 수정하거나 outbox를 우회하지
않는다. 정책 변경은 항상 `cowork-preference`의 command와 outbox 경계를 거쳐 projection에 전파된다.
