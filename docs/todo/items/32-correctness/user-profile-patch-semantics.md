# 사용자 프로필 PATCH 부분 수정 의미 보장

- **서비스**: cowork-user
- **우선순위**: 🔴 높음
- **현재 상태**: `PATCH /users/me`에서 미제공 필드가 기존 값 유지가 아니라 `nil` 또는 빈 역할 목록으로 해석됨

## 문제

`cowork-user/lib/cowork_user/router.ex`는 `PATCH /users/me`의 JSON 본문 전체를 `Accounts.update_my_profile/2`에 전달한다. `cowork-user/lib/cowork_user/accounts.ex`의 해당 함수는 `nickname`과 `description`을 항상 `Map.get/2`로 읽어 changeset에 넣으므로 키가 없을 때도 두 필드를 `nil`로 갱신한다. 반면 `name`과 `github_id`는 `build_profile_account_attrs/2`에서 `Map.has_key?/2`로 미제공 여부를 구분해 동작이 서로 다르다.

같은 함수는 미제공 `roles`를 `normalize_roles(nil)`에서 빈 목록으로 바꾼 뒤 `replace_roles_in_transaction/2`를 항상 호출한다. 이 함수가 먼저 기존 `tb_profile_roles` 행을 모두 삭제하므로, 이름이나 GitHub ID만 수정한 요청도 nickname·description을 지우고 역할 전체를 제거할 수 있다.

`cowork-user/lib/cowork_user/open_api.ex`의 update profile schema에는 `required` 목록이 없어 `nickname`, `name`, `description`, `github_id`, `roles`가 모두 선택 필드로 노출되어 있다. 그러나 미제공, 명시적 `null`, 빈 배열의 의미가 구현과 계약에 고정되어 있지 않다. `cowork-user/test/`에는 `update_my_profile/2` 또는 이 부분 수정 계약을 검증하는 테스트가 없고, 현재 `V1`부터 `V20`까지의 migration에도 이 요청 의미를 보정하는 후속 변경은 없다.

## 필드별 PATCH 정책

| 필드 | 미제공 | 명시적 `null` | 값 제공 |
|------|--------|---------------|---------|
| `nickname`, `description` | 기존 값을 유지함 | 값을 비우고 OpenAPI에 `nullable` 계약을 명시함 | 제공한 값으로 변경함 |
| `name` | 기존 값을 유지함 | 유효성 오류로 거부함 | 제공한 값으로 변경함 |
| `github_id` | 기존 값을 유지함 | 값을 비움 | 제공한 값으로 변경함 |
| `roles` | 기존 목록을 유지함 | 배열 형식 오류로 거부함 | 빈 배열은 전체 해제하고 비어 있지 않은 배열은 전체 교체함 |

## 할 일

### 변경 필드 판별

- `nickname`과 `description`을 `Map.has_key?/2`로 선별해 실제로 제공된 필드만 `Profile.changeset/2`에 전달한다.
- `roles` 키가 있을 때만 역할 교체를 실행하고, 미제공과 명시적 `null`을 정규화 전에 구분한다.
- `roles`의 배열 원소 검증과 중복 제거를 유지하면서 `null`과 배열이 아닌 값은 명시적인 validation error로 반환한다.
- profile 필드와 account 필드가 모두 미제공된 요청을 no-op으로 처리할지 validation error로 거부할지 API 계약에 고정한다.
- 실제 변경이 발생한 경우에만 `user.profile.event` outbox를 적재하도록 불필요한 projection event 생성 여부를 정리한다.

### API 계약과 회귀 테스트

- `cowork-user/lib/cowork_user/open_api.ex`의 schema에 필드별 `nullable`과 빈 배열 의미를 반영한다.
- 기존 profile을 준비한 뒤 단일 필드만 제공하는 `Accounts.update_my_profile/2` database 테스트를 추가한다.
- `PATCH /users/me`의 미제공, 명시적 `null`, 빈 배열 조합을 router 수준에서 검증한다.
- `name`과 `github_id`의 기존 key-presence 동작이 profile 필드 변경과 일관되게 유지되는지 고정한다.

## 검증

- `name`만 수정한 뒤 기존 `nickname`, `description`, `roles`가 그대로인지 확인한다.
- `description`만 수정한 뒤 나머지 profile과 account 필드가 그대로인지 확인한다.
- `roles`를 생략하면 기존 역할이 유지되고 빈 배열을 보내면 전체 역할이 해제되는지 검증한다.
- `roles: null`은 `400`으로 거부되고 `nickname: null`, `description: null`은 계약대로 해당 값만 비우는지 확인한다.
- 부분 수정 뒤 응답과 `user.profile.event` payload가 transaction의 최종 profile 상태와 일치하는지 검증한다.
- `cowork-user`에서 `mix test`를 실행해 profile, OpenAPI, Kafka profile event 회귀 테스트가 통과하는지 확인한다.

## 완료 조건

- `PATCH /users/me`의 미제공 필드는 기존 값을 변경하지 않는다.
- 명시적 `null`과 빈 `roles` 배열의 의미가 OpenAPI와 구현에서 일치한다.
- 한 필드의 부분 수정이 다른 profile 또는 account 필드를 초기화하지 않는다.
- 미제공·`null`·빈 배열 조합의 회귀 테스트가 갖춰져 있다.
