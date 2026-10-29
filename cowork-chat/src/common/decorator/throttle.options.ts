export type ThrottleOptions = {
	/**
    Redis rate limit 키 네임스페이스. 사용자 ID와 결합해 `{key}:{userId}` 형태로 사용된다.
    */
	key: string;
	/**
    시간창(ms) 값을 오버라이드할 환경설정 키
    */
	windowMsConfigKey: string;
	/**
    최대 요청 수 값을 오버라이드할 환경설정 키
    */
	maxRequestsConfigKey: string;
	defaultWindowMs: number;
	defaultMaxRequests: number;
	/**
    한도 초과 시 반환할 메시지 (기본값: 공용 안내 문구)
    */
	message?: string;
};

export const THROTTLE_OPTIONS_KEY = 'throttle:options';
