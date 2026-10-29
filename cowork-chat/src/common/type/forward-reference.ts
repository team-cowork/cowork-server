/**
`forwardRef`로 주입하는 순환 의존성의 타입이다.
ESM에서는 순환 import된 클래스가 아직 초기화되지 않았을 수 있으므로, 이 타입을 쓰면 `design:type` 메타데이터에
클래스 대신 `Object`가 기록되어 모듈 로드 중 참조 오류가 나지 않는다. 주입 토큰은 `forwardRef`가 제공한다.
*/
export type ForwardReference<T> = T;
