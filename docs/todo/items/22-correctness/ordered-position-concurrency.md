# 정렬 position 동시성 보장

- **서비스**: cowork-channel, cowork-project, cowork-roadmap
- **우선순위**: 🟠 중간
- **현재 상태**: 생성 시 `MAX(position) + 1` 또는 sibling `COUNT`를 잠금 없이 사용하며 scope별 position 유일성도 보장하지 않음

## 문제

`CreateChannelServiceImpl`과 `CreateProjectServiceImpl`은 팀의 현재 최대 `position`을 조회해 1을 더한 값을 새 row에 저장한다. 최대값 조회와 insert 사이에 팀 단위 잠금이나 원자 allocator가 없으므로 같은 팀에서 동시에 생성된 두 요청이 동일한 최대값을 읽을 수 있다.

두 테이블에는 `(team_id, position)` 조회 index만 있고 유일성 제약은 없다. 경합이 발생해도 요청은 모두 성공하며 중복 position이 남는다. 조회가 ID를 보조 정렬 기준으로 사용할 수는 있지만 사용자가 지정한 순서의 의미가 모호해지고, 전체 row를 잠그는 reorder와 잠금 없이 들어오는 create가 겹치면 최신 순서가 예상과 다르게 반영될 수 있다.

`CreateRoadmapNodeServiceImpl.nextPosition`은 같은 roadmap과 parent 아래 sibling 수를 `COUNT`해 새 position으로 사용한다. 동시 생성은 같은 count를 읽을 수 있고, 삭제 후 position에 gap이 생긴 상태에서는 동시성이 없어도 `COUNT` 값이 기존 position과 충돌할 수 있다. roadmap node도 `(roadmap_id, parent_id, position)` 유일성이나 원자 할당 정책이 없다.

단순 unique 제약만 추가하면 다건 reorder 중 일시적인 position 충돌이 생길 수 있다. 생성·재정렬을 같은 팀 단위 직렬화 정책으로 묶거나 충돌 없는 order key 전략을 선택한다.

## 구현 선택지

| 선택지 | 장점 | 주의점 |
|--------|------|--------|
| scope row 또는 전용 allocator row 잠금 | 연속 정수 position을 유지하기 쉬움 | 팀·roadmap parent별 로컬 lock row가 필요할 수 있음 |
| scope별 sequence/allocator table | 생성 경합을 짧게 직렬화함 | 삭제·reorder 뒤 gap 정책을 정의해야 함 |
| sparse order key 또는 rank token | 중간 삽입과 재정렬 write를 줄임 | key 재균형과 클라이언트 계약 변경이 필요함 |

## 할 일

### 생성·정렬 정책

- 채널·프로젝트의 팀 단위와 roadmap node의 `(roadmapId, parentId)` 단위 position 할당 방식을 정한다.
- roadmap node의 `COUNT` 기반 할당을 제거하고 gap이 있어도 기존 position과 충돌하지 않는 방식으로 바꾼다.
- 각 scope의 생성과 전체 reorder가 같은 직렬화 경계를 사용하게 한다.
- 중복 position이 이미 존재하는지 점검하고 안정적인 `(position, id)` 순서로 재번호화한다.
- 가능한 경우 최종 상태의 `(team_id, position)`과 roadmap sibling position 유일성을 database가 보장하게 한다. nullable `parent_id`의 DB별 unique semantics도 반영한다.
- reorder 중 unique 충돌이 생기지 않는 2단계 갱신 또는 allocator 방식을 구현한다.

### 이벤트

- 경합 해결 뒤 발행되는 channel·project·roadmap event의 position이 최종 database 값과 일치하게 한다.
- 동시 reorder 요청의 승자·충돌 응답 정책을 명확히 한다.

## 검증

- 중복·누락 ID, 다른 scope ID, 잘못된 순열을 거부하는 reorder 비즈니스 규칙을 서비스 단위 테스트로 검증한다.
- gap이 있는 sibling 집합에서 다음 position을 결정하는 순수 정책을 단위 테스트로 검증한다.
- 생성·reorder의 lock 경계와 최종 event 값은 transaction 선언과 호출 그래프를 정적으로 점검한다.
- 동시 요청의 직렬화, unique 제약 충돌, migration 결과는 배포 전 dry-run과 runtime metric·데이터 점검으로 확인한다.
- database 경합과 event 순서를 고정하는 동시성 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- 같은 팀의 채널·프로젝트와 같은 roadmap parent의 node에 중복 position이 저장되지 않는다.
- 생성과 reorder 경합의 결과가 결정적이며 문서화되어 있다.
- database 상태와 projection event의 position이 일치한다.
- 핵심 정렬 규칙의 단위 테스트와 동시성 운영 검증 절차가 세 서비스에 마련되어 있다.
