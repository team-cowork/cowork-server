# cowork-chat

실시간 채팅과 첨부파일·검색을 담당한다.

운영 WebSocket은 Gateway의 `/ws/chat`을 사용한다. Kafka source 교체 시의 재구축 제약은
[상태 토픽 전환](../docs/kafka-state-topic-cutover.md)을 따른다.

[로컬 실행](../docs/local-run-guide.md) · [운영 배포](../docs/deployment.md)
