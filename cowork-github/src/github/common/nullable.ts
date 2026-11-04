const absent = null;

/**
GitHub API 응답과 Kafka 이벤트에서 `null`로 전달하는 값의 타입이다.
필드가 없음을 뜻하는 `undefined`와 계약이 다르므로 `undefined`로 바꾸지 않는다.
*/
export type Nullable<T> = T | typeof absent;

export const isPresent = <T>(value: Nullable<T> | undefined): value is T => value !== undefined && value !== absent;
