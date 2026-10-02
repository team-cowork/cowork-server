# 정렬 position 동시성 보장

- **서비스**: cowork-channel, cowork-project, cowork-roadmap
- **우선순위**: 🟠 중간
- **현재 상태**: 채널·프로젝트 생성은 `MAX(position) + 1`, roadmap node는 sibling 수를 사용하며 scope별 유일성을 보장하지 않는다.

## 문제

같은 scope에서 동시에 생성하면 동일 position을 선택할 수 있다. roadmap node는 삭제로 gap이
생긴 상태에서 sibling 수를 다음 값으로 사용하므로 순차 생성에서도 기존 값과 충돌할 수 있다.

unique만 추가하면 여러 행을 재정렬하는 중간 상태에서 충돌할 수 있다.
생성·reorder를 함께 직렬화할 정책과 기존 중복·gap 처리 방침이 필요하다.

## 선택할 전략

| 전략 | 결정할 제약 |
|---|---|
| scope row·allocator 잠금 | scope별 잠금 소유권과 생성·reorder 직렬화 |
| sequence·allocator table | 삭제 후 gap과 재정렬 정책 |
| sparse rank | key 재균형과 클라이언트 계약 변경 |

## 할 일

- 채널·프로젝트의 팀 scope와 node의 `(roadmapId, parentId)` scope에 적용할 전략을 정한다.
- sibling 수 기반 할당을 제거하고 생성·reorder가 같은 직렬화 경계를 사용하게 한다.
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
