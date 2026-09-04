# 통합 검색의 비공개 채널 노출 차단

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: 완료 — 통합 검색과 채널 조회 경로에서 비공개 채널 멤버십을 일관되게 검사함

> **2026-08-30 완료:** `cowork-chat` 통합검색이 활성 채널 멤버십 ID를 일괄 조회해 공개 채널과 가입한 비공개 채널만
> 반환하도록 수정했다. 같은 가시성 계약을 `cowork-channel`의 검색·팀 목록·프로젝트 목록·단건 조회에도 적용했으며,
> `cowork-chat` 전체 357개 테스트와 build·lint, `cowork-channel` 전체 테스트와 ktlint를 통과했다.

> **2026-09-03 현황:** 이후 역할 기반 읽기 인가도 적용되어 현재 채널 검색은 아래 멤버십 조건과 effective `message_read` 정책을 함께 확인한다. built-in `OWNER` 예외와 정책 기본 거부 범위는 [역할 기반 읽기 권한](../36-security/role-based-channel-message-read-authorization.md)을 따른다. 아래 문제 설명은 최초 점검 당시 상태다.

## 발견 당시 문제

`cowork-chat/src/chat/unified-search.resolver.ts`의 `UnifiedSearchResolver.unifiedSearch`는 `POST /api/chat/graphql`에서 메시지 검색과 채널 검색을 병렬로 실행한다. 메시지 검색은 `ChatService.searchTeamMessages`에서 요청자의 채널 멤버십 projection을 조회해 접근 가능한 채널 ID로 Elasticsearch 검색 범위를 제한한다.

반면 `ChannelSearchClient.searchChannels`는 `TeamMemberProjectionRepository.exists`로 팀 멤버 여부만 확인한 뒤 `ChannelProjectionRepository.searchByTeamAndName`을 호출한다. 이 쿼리는 `teamId`, 삭제 여부, 채널명만 필터링하며 `isPrivate`나 요청자의 채널 멤버십은 조건에 포함하지 않는다.

따라서 같은 팀의 일반 멤버가 가입하지 않은 비공개 채널의 ID, 이름, 타입, 설명, 공개 여부를 검색 결과로 확인할 수 있다. 하나의 통합 검색 응답 안에서도 메시지는 채널 멤버십으로 보호되고 채널 메타데이터는 팀 멤버십만으로 노출되는 서로 다른 가시성 계약이 적용되어 있다.

## 멤버십 가시성 계약

다음 조건은 멤버십 측면의 최소 조건이며, 현재 구현에서는 역할 기반 읽기 정책도 통과해야 한다.

| 채널 상태 | 검색 노출 조건 | 반환 정책 |
|-----------|----------------|-----------|
| `isPrivate=false`인 팀 채널 | 요청자가 해당 팀의 활성 멤버임 | 이름이 검색어와 일치하면 반환함 |
| `isPrivate=true`인 팀·프로젝트 채널 | 요청자가 해당 채널의 활성 멤버임 | 가입한 비공개 채널만 반환함 |
| 삭제된 채널 | 없음 | 항상 제외함 |
| 팀에 속하지 않는 DM 채널 | 팀 범위 검색 대상이 아님 | 항상 제외함 |

채널 projection과 멤버십 projection 중 어느 한쪽이라도 삭제 상태이거나 접근 여부를 확정할 수 없으면 비공개 채널은 노출하지 않는다. 메시지 검색과 채널 검색이 동일한 활성 멤버십 정의를 사용하도록 가시성 판정을 공통화한다.

## 할 일

### 조회 경로

- `ChannelSearchClient.searchChannels`가 요청자 ID를 실제 채널 가시성 필터에 사용하도록 변경한다.
- `ChannelProjectionRepository.searchByTeamAndName`과 `ChannelMemberRepository`를 조합해 공개 채널과 가입한 비공개 채널만 한 번에 조회한다.
- 채널마다 멤버십을 개별 조회하는 N+1 쿼리를 만들지 않고, 집계 쿼리나 일괄 멤버십 조회를 사용한다.
- 채널 및 멤버십 tombstone을 모두 반영해 탈퇴하거나 삭제된 비공개 채널이 검색 결과에 남지 않게 한다.
- `UnifiedSearchResolver.unifiedSearch`와 GraphQL 설명에 실제 가시성 계약을 반영한다.

### 회귀 방지

- 채널 검색 전용 서비스 테스트에 공개 채널, 가입한 비공개 채널, 미가입 비공개 채널, 삭제된 채널 사례를 추가한다.
- 통합 검색 테스트에서 메시지 결과와 채널 결과가 같은 요청자 접근 범위를 사용하는지 확인한다.
- 팀 멤버가 아닌 요청을 기존과 같이 `ForbiddenException`으로 거부한다.

## 검증

- 팀 멤버이지만 비공개 채널 멤버가 아닌 사용자가 해당 채널명을 정확히 입력해도 채널 ID와 메타데이터를 받지 못하는지 검증한다.
- 비공개 채널 멤버는 같은 검색어로 해당 채널을 조회할 수 있는지 검증한다.
- 공개 채널은 팀 멤버에게 계속 노출되고 팀 외부 사용자에게는 노출되지 않는지 검증한다.
- `channel.member.event`의 `LEAVE` 적용 직후 통합 검색 결과에서 비공개 채널이 제외되는지 검증한다.
- 여러 채널을 검색할 때 채널 수에 비례하는 멤버십 쿼리가 발생하지 않는지 확인한다.

## 완료 조건

- 미가입 비공개 채널의 ID, 이름, 타입, 설명, 공개 여부가 통합 검색 응답에 포함되지 않는다.
- 메시지 검색과 채널 검색이 동일한 활성 채널 멤버십 경계를 사용한다.
- 공개 채널과 가입한 비공개 채널의 정상 검색 동작이 자동 테스트로 보호되어 있다.
- 탈퇴 및 삭제 projection이 적용된 채널은 이후 검색 결과에 노출되지 않는다.
