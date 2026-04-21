import { UnauthorizedException } from '@nestjs/common';
import { RequestContextUtil } from './request-context.util';

describe('RequestContextUtil', () => {
    describe('getUserId', () => {
        it('헤더에서 userId를 숫자로 파싱한다', () => {
            expect(RequestContextUtil.getUserId({ 'x-user-id': '42' })).toBe(42);
        });

        it('배열 헤더일 경우 첫 번째 값을 사용한다', () => {
            expect(RequestContextUtil.getUserId({ 'x-user-id': ['99', '1'] })).toBe(99);
        });

        it('x-user-id 헤더가 없으면 UnauthorizedException을 던진다', () => {
            expect(() => RequestContextUtil.getUserId({})).toThrow(UnauthorizedException);
        });

        it('x-user-id가 숫자가 아니면 UnauthorizedException을 던진다', () => {
            expect(() => RequestContextUtil.getUserId({ 'x-user-id': 'abc' })).toThrow(
                UnauthorizedException,
            );
        });
    });

    describe('getUserRole', () => {
        it('헤더에서 role을 반환한다', () => {
            expect(RequestContextUtil.getUserRole({ 'x-user-role': 'ROLE_ADMIN' })).toBe(
                'ROLE_ADMIN',
            );
        });

        it('헤더가 없으면 기본값 ROLE_USER를 반환한다', () => {
            expect(RequestContextUtil.getUserRole({})).toBe('ROLE_USER');
        });
    });
});
