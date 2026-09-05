# Gateway JSON 응답 전체 버퍼링 제거

- **서비스**: cowork-gateway, Gateway 경유 JSON API
- **우선순위**: 🟠 중간
- **현재 상태**: `ApiResponseWrapperFilter`가 `Content-Length` 없는 JSON 응답을 전부 수집한 뒤에야 1MB 제한을 검사함

## 문제

`ApiResponseWrapperFilter`는 JSON 응답을 `CommonApiResponse`로 감싸기 위해 `Flux<DataBuffer>`를 `collectList`로 모두 수집한다. `Content-Length`가 1MB를 넘는 경우에는 수집 전에 통과시키지만, chunked 또는 길이를 알 수 없는 응답은 실제 크기와 관계없이 먼저 전량 메모리에 적재한다.

수집 뒤 크기가 1MB를 넘으면 wrapping을 건너뛰지만 `DataBufferUtils.join`으로 다시 하나의 buffer를 만든다. 제한은 이미 발생한 heap·direct buffer 점유를 줄이지 못하며, 작은 응답도 원본 buffer, `ByteArray`, JSON tree, 직렬화 결과를 거쳐 여러 번 복사된다. `writeAndFlushWith`도 평탄화되어 streaming과 backpressure 특성이 사라진다.

기존 필터 테스트는 응답 wrapping과 buffer 구현 세부를 고정하는 기술 테스트였으므로 저장소 테스트 범위에서 제거한다. 일반 wrapping, 대용량 chunked 응답, 취소 시 buffer release, streaming 통과 동작은 코드 검토와 runtime 관측으로 확인한다.

## 응답 전략

| 응답 유형 | 목표 처리 |
|-----------|-----------|
| 작은 길이 명시 JSON | 제한된 크기 안에서만 wrapping함 |
| 길이 미상·chunked JSON | 기본적으로 통과시키거나 bounded aggregation을 적용함 |
| streaming·SSE·파일 | 절대 wrapping하지 않고 backpressure를 유지함 |
| 이미 `CommonApiResponse`인 응답 | 재직렬화 없이 그대로 전달함 |

장기적으로는 각 HTTP 서비스가 공통 응답 envelope를 소유하고 Gateway가 body를 해석하지 않는 방향을 우선 검토한다.

## 할 일

### 필터 경계

- wrapping 대상 content type, endpoint, 응답 크기 계약을 명시한다.
- 길이 미상 응답을 무제한 수집하지 않는 bounded 처리 또는 bypass를 구현한다.
- 초과 판단 뒤에도 전체 body를 하나로 join하지 않고 원래 stream을 보존하는 구조를 적용한다.
- cancel·오류·초과 경로에서 모든 `DataBuffer`가 정확히 release되는지 보장한다.
- 필터에서 JSON parse·직렬화를 반복하지 않도록 downstream envelope 전환 계획을 세운다.

### 관측

- wrapping·bypass·크기 초과 건수와 변환 시간, 응답 크기를 metric으로 수집한다.
- 최대 동시 요청에서 heap과 Netty direct memory 사용량을 기준선과 비교한다.

## 검증

- wrapping·bypass 조건과 bounded aggregation 상한을 필터 코드와 설정에서 정적으로 점검한다.
- `Content-Length`가 큰 응답, 길이 미상의 chunked 응답, streaming 응답의 처리 방식은 staging trace와 metric으로 확인한다.
- 중간 취소와 downstream 오류의 buffer release 경로는 코드 검토와 Netty leak detection 관측으로 확인한다.
- 대용량 동시 응답의 heap·direct memory 추이는 통제된 부하 관측으로 확인한다.
- response wrapping, chunking, buffer lifecycle을 고정하는 자동화 단위·통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- 길이 미상·대용량 응답이 Gateway 메모리에 무제한 집계되지 않는다.
- streaming 응답의 chunk와 backpressure가 유지된다.
- 응답 wrapping의 크기·경로 계약이 문서화되고 buffer lifecycle을 metric과 leak detection으로 확인할 수 있다.
