# 정렬 position 동시성 보장

- **서비스**: cowork-channel, cowork-project, cowork-roadmap
- **우선순위**: 🟠 중간
- **현재 상태**: 채널·프로젝트의 생성·reorder 공통 직렬화와 세 모듈의 기존 중복 정리·유일성 제약이 남아 있다.

## 문제

기존 행의 reorder 잠금만으로 신규 생성의 `MAX(position) + 1`과 같은 직렬화 경계를 보장할 수 없다.
유일성 제약만 추가해도 다건 reorder의 중간 상태에서 충돌할 수 있으므로 할당·재정렬 정책을 함께 정한다.

roadmap은 로드맵 → 노드 잠금 순서를 유지한다. 잠금 전 조회의 오래된 snapshot을 피하도록
`READ COMMITTED`에서 잠금 후 다시 읽으며, 빈 scope의 gap lock이 다른 로드맵 insert와 교착하는 `FOR SHARE` 방식은 사용하지 않는다.
실제 경합 결과는 아직 수동 확인하지 않았다.

## 할 일

- 채널·프로젝트의 팀 scope, node의 `(roadmapId, parentId)`, reference의 `nodeId` scope별 직렬화·할당 전략을 확정한다.
- 채널·프로젝트의 생성·reorder가 같은 경계를 사용하게 하고 보이지 않는 채널의 position 보존 정책을 유지한다.
- 기존 중복은 안정적인 `(position, id)` 순서로 정리한다.
- nullable parent, 삭제 후 gap, 다건 reorder의 중간 충돌을 처리한 유일성 제약을 적용한다.
- 동시 reorder의 승자·충돌 응답과 최종 event 값을 확정한다.

## 검증

- 잘못된 순열·다른 scope·중복 ID 거부와 gap의 다음 값 정책을 핵심 단위 테스트로 확인한다.
- 잠금·마이그레이션·event 값은 정적으로, 실제 생성·reorder 경합과 기존 데이터 정리는 수동 확인한다.

## 완료 조건

- 같은 scope의 최종 position이 중복되지 않는다.
- 생성·reorder 경합과 gap 처리 결과가 결정적이다.
- 저장값과 projection event가 같은 최종 순서를 표현한다.
