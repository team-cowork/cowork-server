const absent = null;

/**
MongoDB 문서, Kafka 이벤트, API 응답에서 `null`로 저장·전송하는 값의 타입이다.
필드가 없음을 뜻하는 `undefined`와 계약이 다르므로 `undefined`로 바꾸지 않는다.
*/
export type Nullable<T> = T | typeof absent;
