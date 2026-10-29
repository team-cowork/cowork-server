/**
권한 키별 boolean 맵 계약. 배열이나 boolean이 아닌 값이 섞인 객체는 허용하지 않는다.
*/
export function isBooleanRecord(value: unknown): value is Record<string, boolean> {
	return typeof value === 'object'
		&& value !== null
		&& !Array.isArray(value)
		&& Object.values(value).every(entry => typeof entry === 'boolean');
}
