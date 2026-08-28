import { BadRequestException } from '@nestjs/common';
import { SafePositiveIntPipe } from './safe-positive-int.pipe';

describe('SafePositiveIntPipe', () => {
    const pipe = new SafePositiveIntPipe();

    it('양의 정수 문자열을 number로 변환한다', () => {
        expect(pipe.transform('1')).toBe(1);
        expect(pipe.transform('42')).toBe(42);
    });

    it('JavaScript 안전 정수 범위 내 최대값은 통과한다', () => {
        expect(pipe.transform(String(Number.MAX_SAFE_INTEGER))).toBe(Number.MAX_SAFE_INTEGER);
    });

    it.each(['0', '-1', '1.5', 'abc', '', ' 1', '1 ', '+1', '01', '1e3'])(
        '양의 정수 형식이 아니면 BadRequestException을 던진다 (value=%s)',
        (value) => {
            expect(() => pipe.transform(value)).toThrow(BadRequestException);
            expect(() => pipe.transform(value)).toThrow('ID must be a positive integer');
        },
    );

    it('JavaScript 안전 정수 범위를 초과하면 BadRequestException을 던진다', () => {
        const overflow = '9'.repeat(String(Number.MAX_SAFE_INTEGER).length + 1);

        expect(() => pipe.transform(overflow)).toThrow(BadRequestException);
        expect(() => pipe.transform(overflow)).toThrow('ID exceeds the JavaScript safe integer range');
    });
});
