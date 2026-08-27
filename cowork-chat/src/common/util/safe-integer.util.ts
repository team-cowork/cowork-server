/** Numeric JSON/HTTP ID 계약에서 IEEE-754 반올림 없이 표현 가능한 양의 정수만 허용한다. */
export function isSafePositiveInteger(value: unknown): value is number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}
