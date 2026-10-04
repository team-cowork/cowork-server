# 정렬 position 동시성 보장

- **서비스**: cowork-channel, cowork-project, cowork-roadmap
- **우선순위**: 🟠 중간
- **현재 상태**: 채널·프로젝트 reorder에는 행 잠금이 있으나 생성과 공통 직렬화가 없으며 roadmap node·reference는 개수로 position을 할당한다.

## 문제

같은 scope에서 동시에 생성하면 동일 position을 선택할 수 있다. 채널·프로젝트 reorder의 기존 행
잠금만으로 신규 생성의 `MAX(position) + 1`과 같은 직렬화 경계가 보장되지는 않는다.
roadmap node와 node reference는 삭제로 gap이 생기면 개수 기반 할당이 순차 생성에서도 기존 값과 충돌한다.
roadmap node reorder도 잠금 없는 목록을 읽어 저장한다.

unique만 추가하면 여러 행을 재정렬하는 중간 상태에서 충돌할 수 있다.
생성·reorder를 함께 직렬화할 정책과 기존 중복·gap 처리 방침이 필요하다.

## 선택할 전략

| 전략 | 결정할 제약 |
|---|---|
| scope row·allocator 잠금 | scope별 잠금 소유권과 생성·reorder 직렬화 |
| sequence·allocator table | 삭제 후 gap과 재정렬 정책 |
| sparse rank | key 재균형과 클라이언트 계약 변경 |

## 코드 근거

- [Channel reorder](../../../../cowork-channel/src/main/kotlin/com/cowork/channel/domain/channel/service/impl/ReorderTeamChannelsServiceImpl.kt#L34): 기존 행을 잠그고 읽을 수 있는 채널의 position slot을 보존한다. MAX 기반 생성과 공유하는 scope 잠금은 없다.
- [Project 생성](../../../../cowork-project/src/main/kotlin/com/cowork/project/domain/project/service/impl/CreateProjectServiceImpl.kt#L35): MAX(position) + 1을 사용하며 scope별 unique 제약·allocator가 없다.
- [Node reference 생성](../../../../cowork-roadmap/src/main/java/com/cowork/roadmap/domain/node/service/impl/CreateNodeReferenceServiceImpl.java#L33): reference 개수를 다음 position으로 사용하므로 삭제 후 gap에서도 중복될 수 있다.

## 할 일

- 채널·프로젝트의 팀 scope, node의 `(roadmapId, parentId)`, reference의 `nodeId` scope에 적용할 전략을 정한다.
- node·reference의 개수 기반 할당을 제거하고 생성·reorder가 같은 직렬화 경계를 사용하게 한다.
- 기존 채널·프로젝트 reorder의 잠금과 보이지 않는 채널의 position 보존 정책을 유지한다.
- 기존 중복은 안정적인 `(position, id)` 순서로 정리한다.
- 유일성 제약을 적용할 때 nullable parent와 다건 reorder의 중간 충돌을 처리한다.
- 동시 reorder의 승자·충돌 응답과 최종 event 값을 확정한다.

## 검증

- 잘못된 순열·다른 scope·중복 ID 거부와 gap의 다음 값 정책을 서비스·순수 정책 단위 테스트로 확인한다.
- 잠금·migration·event 값은 정적으로 점검한다.
- 실제 생성·reorder 경합과 migration 결과는 수동 데이터 점검으로 확인한다.

## 완료 조건

- 같은 scope의 최종 position이 중복되지 않는다.
- 생성·reorder 경합과 gap 처리 결과가 결정적이다.
- 저장값과 projection event가 같은 최종 순서를 표현한다.
