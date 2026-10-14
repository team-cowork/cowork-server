# 채팅 projection 증분 재개와 재구축 모드 분리

- **서비스**: cowork-chat
- **우선순위**: 🟠 중간
- **현재 상태**: broker identity 확보와 부분 데이터 유실·source 교체의 복구 기준이 남아 있다.

## 문제

운영자가 관리하는 `CHAT_PROJECTION_SOURCE_GENERATION`·MongoDB dataset identity·offset 범위만으로는
같은 이름·세대·겹치는 offset을 가진 broker topic 교체를 탐지할 수 없다.
collection의 완전 소실 검사도 일부 문서 유실을 탐지하지 못한다.

운영 source 세대를 broker UUID와 동등한 증거로 취급하지 않는다.
공통 복구 계약은 [Kafka 규칙](../../../../../.claude/rules/kafka-projections.md)을 따른다.

## 할 일

- broker topic UUID를 확보·저장·검증할 client 경계를 마련한다.
- 확보 전 source 교체·손상·연속성 불명은 fail closed와 명시적 dataset 재구축으로 처리한다.
- 부분 유실의 발견·재구축 기준과 source 교체 정보의 공급 경계를 정한다.
- 여러 replica의 pause·lease fencing·reset·replay·중단 후 재개를 확인한다.
- `channelMember` 재구축 때 채팅 소유 필드를 보존한다.
- 재시작·retention gap·MongoDB 초기화·topic 교체별 복구 기준과 증분 성능을 확인한다.

## 검증

- 관리 경로의 `ADMIN` 인가·불완전 상태의 접근 거부만 핵심 보안 단위 테스트로 확인한다.
- identity·dataset·checkpoint·barrier 상태 전이는 정적으로 확인한다.
- restart·rebalance·rebuild·replica fencing·필드 보존은 실제 데이터·지표와 수동 운영 점검으로 확인한다.

## 완료 조건

- broker 연속성을 확인한 경우에만 checkpoint를 재사용한다.
- 교체·손상·retention gap은 이전 worker를 차단하고 projection·checkpoint·barrier를 함께 재구축한다.
- 채팅 소유 필드가 보존되며 복구·성능 결과를 확인할 수 있다.
