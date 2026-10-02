# Gateway JSON 응답 전체 버퍼링 제거

- **서비스**: cowork-gateway, Gateway 경유 JSON API
- **우선순위**: 🟠 중간
- **현재 상태**: bounded 집계·초과 시 원본 전달·streaming 우회·지표가 구현되어 있으며 실제 메모리·buffer 해제 확인이 남아 있다.

## 문제

현재 `ApiResponseWrapperFilter`는 `BoundedResponseBodyTransformer`로 집계 상한을 적용하고
`writeAndFlushWith`는 그대로 전달한다. 길이 미상 응답을 끝까지 모으던 이전 설명은 현재 코드에
해당하지 않는다.

대용량 chunked 응답·취소·downstream 오류에서의 실제 heap·direct memory와 buffer 해제 결과는
아직 관측하지 않았다. 상한 초과 판단에는 입력 chunk가 포함되므로 chunk 크기도 함께 확인한다.

## 할 일

- 검증 환경에서 작은 JSON·길이 미상·상한 초과·SSE·파일 응답의 전달과 wrapping 지표를 확인한다.
- 동시 대용량 응답의 heap·direct memory와 Netty leak detection을 관측한다.
- 취소·downstream 오류의 release 경로를 정적으로 검토하고 운영 관측 결과와 대조한다.
- 실제 응답 크기·동시 요청 기준으로 집계 상한과 알림 기준을 정한다.

## 검증

- 초과 후 전체 body를 다시 집계하지 않는지 정적으로 확인한다.
- streaming chunk·flush와 실제 응답의 status·header·body를 수동 확인한다.
- wrapping·bypass·초과 지표와 메모리·누수 관측 결과를 기록한다.

## 완료 조건

- 길이 미상·대용량 응답이 무제한 집계되지 않고 streaming 전달이 유지된다.
- 취소·오류 경로의 buffer 해제와 허용 메모리 범위를 실제 관측으로 확인할 수 있다.
