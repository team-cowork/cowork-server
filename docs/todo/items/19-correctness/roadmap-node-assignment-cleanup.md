# 로드맵 노드 삭제 시 assignment 무결성 보장

- **서비스**: cowork-roadmap
- **우선순위**: 🔴 높음
- **현재 상태**: 과제가 연결된 서브트리 삭제 거부와 고아 과제 정리 migration은 반영되어 있으며 GLOBAL 정책과 운영 확인이 남아 있다.

## 문제

현재 노드 삭제는 서브트리에 과제가 있으면 거부한다. `GLOBAL` 과제 scope 자체는 거부하지만,
팀 관리자는 `GLOBAL` 로드맵의 노드에 `TEAM`·`PROJECT` 과제를 만들 수 있다.
따라서 한 팀의 과제가 공용 노드 삭제를 막는 정책 문제는 남아 있다.

다른 팀의 진행 기록을 지우지 않고 공용 로드맵을 관리할 정책이 필요하다.
기존 고아 과제를 삭제하는 `V7__delete_orphan_node_assignments.sql`의 실제 데이터 적용과
노드·과제 생성의 잠금 경계도 아직 운영에서 확인하지 않았다.

## GLOBAL 정책 선택

| 선택지 | 확인할 영향 |
|---|---|
| 사용 중 삭제 거부 유지 | 팀별 과제 해제 책임과 공용 노드 관리 권한 |
| 로드맵 전체 과제로 전환 | 과제 의미·중복 제약·응답 변경 |
| 노드 soft delete | 기존 진행 기록·조회와 신규 과제 생성 제한 |

## 코드 근거

- [노드 삭제](../../../../cowork-roadmap/src/main/java/com/cowork/roadmap/domain/node/service/impl/DeleteRoadmapNodeServiceImpl.java#L53): 노드 배타 잠금과 과제 잠금 조회 뒤 연결 과제가 있으면 409로 거부한다.
- [과제 생성](../../../../cowork-roadmap/src/main/java/com/cowork/roadmap/domain/assignment/service/impl/CreateRoadmapAssignmentServiceImpl.java#L34): GLOBAL 과제 scope는 거부하지만 읽을 수 있는 GLOBAL 로드맵에 팀·프로젝트 과제를 만들 수 있다.

## 할 일

- GLOBAL 정책을 확정하고 노드 삭제·과제 생성·조회에 적용한다.
- 운영 데이터 사본에서 고아 과제 수와 `V7` 삭제 대상을 확인하고 수동 dry-run한다.
- 노드 삭제와 자식·과제 생성의 잠금 직렬화 결과를 수동 확인한다.

## 검증

- GLOBAL·팀 로드맵의 확정된 삭제·과제 보존 규칙을 서비스 단위 테스트로 확인한다.
- migration 전후의 고아 과제와 다른 팀 진행 기록을 대조한다.
- transaction·잠금 쿼리는 정적으로 확인하고 실제 적용 결과를 기록한다.

## 완료 조건

- GLOBAL 관리가 다른 팀의 진행 기록을 임의로 삭제하지 않는 정책으로 동작한다.
- 삭제된 노드를 참조하는 과제가 남아 있지 않는다.
- 정책 단위 테스트와 실제 데이터·잠금 확인 결과가 기록되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#419](https://github.com/team-cowork/cowork-server/pull/419).
- [대조 코드](../../../../cowork-roadmap/src/main/java/com/cowork/roadmap/domain/node/service/impl/DeleteRoadmapNodeServiceImpl.java): 연결 과제 409 거부·잠금·V7은 구현되었다. PR 리뷰에서 남긴 GLOBAL 노드와 타 팀 과제 보존 정책은 결정되지 않았다.
- 판정: **정책·구현**. GLOBAL 관리·과제 보존 정책과 실제 데이터 전환을 남은 범위로 유지한다.
