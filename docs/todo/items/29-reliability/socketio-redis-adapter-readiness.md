# Socket.IO Redis adapter 준비 상태와 복구 보장

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: Redis adapter 상시 설치·연결 복구·readiness·지표가 구현되어 있으며 실제 client 단절·여러 replica 전달 확인이 남아 있다.

## 문제

현재 adapter는 서버 생성 때 설치하고 Redis 연결 상태에 따라 readiness·WebSocket 트래픽을 제어한다.
초기 timeout 뒤 in-memory로 고정되던 이전 설명은 현재 코드에 해당하지 않는다.

등록 뒤 Eureka 상태 갱신은 별도 수행하지 않으므로 연결 상실의 반영은 실제 readiness 조회 경로에서도
확인한다. 늦은 Redis 기동·한쪽 client 단절·구독 복구와 remote 전달은 아직 운영에서 확인하지 않았다.

## 할 일

- Redis 지연 기동 후 프로세스 재시작 없이 pub/sub 연결과 전달이 복구되는지 확인한다.
- pub·sub client를 각각 끊어 readiness·신규 연결·packet 차단과 복구를 확인한다.
- 두 replica에서 broadcast·room 해제·remote socket 조회를 확인한다.
- 장애 동안 이미 Eureka에 등록된 앱을 Gateway가 어떻게 선택하는지 확인하고 필요한 상태 갱신을 적용한다.
- 종료 후 재연결·구독이 남지 않는지 확인하고 지표·알림 기준을 정한다.

## 검증

- client 상태와 adapter 설치·readiness를 정적으로 대조한다.
- 연결 장애·복구·여러 replica 전달은 수동 운영 점검으로 확인한다.
- degraded 시간·오류·재연결 지표와 실제 트래픽 차단 결과를 기록한다.

## 완료 조건

- 지연 기동과 client 단절 후 같은 프로세스의 Redis adapter가 정상 전달을 회복한다.
- 준비되지 않은 인스턴스로 실시간 트래픽이 유입되지 않는다.
- 여러 replica의 전달·회수와 종료 결과가 기록되어 있다.
