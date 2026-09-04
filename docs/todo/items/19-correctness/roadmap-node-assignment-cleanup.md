# 로드맵 노드 삭제 시 assignment 무결성 보장

- **서비스**: cowork-roadmap
- **우선순위**: 🔴 높음
- **현재 상태**: 노드 서브트리 삭제가 `tb_roadmap_assignments.node_id`를 정리하지 않아 삭제된 노드를 가리키는 assignment를 남김

## 문제

`DeleteRoadmapNodeServiceImpl`은 로드맵의 전체 노드를 읽어 삭제 대상 서브트리 ID를 계산한 뒤 `RoadmapNodeRepository.deleteAllById`만 호출한다. 노드 reference에는 `ON DELETE CASCADE` foreign key가 있지만, `tb_roadmap_assignments.node_id`에는 노드 foreign key가 없고 로드맵 foreign key만 있다.

특정 노드에 연결된 assignment는 노드 삭제 뒤에도 조회되고 상태 변경·삭제 API의 대상이 된다. 응답에는 더 이상 존재하지 않는 `nodeId`가 남아 UI가 과제의 대상 노드를 해석할 수 없고, 같은 노드가 없으므로 정상적인 정합성 복구도 어렵다. 현재 삭제 서비스 테스트는 삭제할 노드 ID 집합만 확인하며 assignment 동작을 검증하지 않는다.

노드가 과제로 사용 중일 때 삭제를 거부할지, assignment도 함께 삭제할지, 로드맵 전체 assignment로 전환할지 제품 정책을 먼저 확정한다.

## 삭제 정책 선택지

| 선택지 | 장점 | 주의점 |
|--------|------|--------|
| assignment가 있으면 삭제 거부 | 과제 기록을 보존함 | 사용자가 assignment를 먼저 정리해야 함 |
| assignment cascade 삭제 | 데이터 구조가 단순하고 고아가 남지 않음 | 진행 기록이 함께 사라짐 |
| 로드맵 전체 assignment로 전환 | 과제 자체를 보존함 | 의미가 바뀌며 중복 unique 충돌 처리 필요 |

## 할 일

### 정책과 migration

- 노드·서브트리 삭제 시 assignment의 목표 동작을 확정한다.
- 기존 고아 assignment를 조회해 삭제·전환·격리하는 데이터 정리 migration을 추가한다.
- cascade 정책이면 같은 서비스 내부의 `node_id` foreign key와 `ON DELETE` 동작을 후속 migration으로 정의한다.
- 거부·전환 정책이면 assignment repository 작업을 노드 삭제와 같은 reactive transaction에 포함한다.

### API 동작

- 삭제가 거부될 때 충돌 이유와 대상 assignment 수를 `409` 응답으로 제공한다.
- assignment가 삭제 또는 전환되면 관련 클라이언트가 목록을 갱신할 수 있는 계약을 정한다.
- 삭제 도중 일부 작업만 반영되지 않도록 하나의 transaction 경계를 적용한다.

## 검증

- assignment가 연결된 루트·자식·손자 노드에 확정된 삭제 정책이 적용되는지 repository mock 기반 서비스 단위 테스트로 검증한다.
- 서브트리 밖의 assignment를 변경 대상으로 선택하지 않는 핵심 범위 규칙을 단위 테스트로 확인한다.
- 노드 삭제와 assignment 작업이 같은 reactive transaction 경계에 있는지 정적으로 점검한다.
- 기존 고아 데이터 정리 결과와 foreign key는 migration dry-run 및 데이터 검사로 확인한다.
- database rollback과 migration을 고정하는 자동화 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- 삭제된 노드를 참조하는 roadmap assignment가 남지 않는다.
- 노드 삭제와 assignment 처리가 하나의 명시된 정책과 transaction으로 동작한다.
- 기존 고아 데이터가 정리되어 있다.
- 서브트리 삭제의 assignment 정책이 서비스 단위 테스트에 포함되어 있다.
