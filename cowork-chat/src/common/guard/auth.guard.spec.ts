import { ExecutionContext, ForbiddenException, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { UserRole } from '../enum/user-role.enum';
import { AuthGuard } from './auth.guard';

const contextFor = (
    headers: Record<string, string> = { 'x-user-id': '42', 'x-user-role': UserRole.USER },
    path = '/chat',
    type = 'http',
): ExecutionContext =>
    ({
        getType: () => type,
        getHandler: jest.fn(),
        getClass: jest.fn(),
        switchToHttp: () => ({ getRequest: () => ({ headers, path }) }),
    }) as unknown as ExecutionContext;

const reflectorFor = (isPublic: boolean, roles?: UserRole[]): Reflector =>
    ({
        getAllAndOverride: jest.fn()
            .mockReturnValueOnce(isPublic)
            .mockReturnValueOnce(roles),
    }) as unknown as Reflector;

describe('AuthGuard', () => {
    describe('canActivate', () => {
        it('HTTP가 아닌 컨텍스트는 사용자 헤더 없이 통과시킨다', () => {
            const guard = new AuthGuard(reflectorFor(false));

            expect(guard.canActivate(contextFor({}, '/chat', 'ws'))).toBe(true);
        });

        it('public endpoint와 metrics endpoint는 사용자 헤더 없이 통과시킨다', () => {
            expect(new AuthGuard(reflectorFor(true)).canActivate(contextFor({}))).toBe(true);
            expect(new AuthGuard(reflectorFor(false)).canActivate(contextFor({}, '/metrics'))).toBe(true);
        });

        it('보호된 endpoint에 유효한 사용자 ID가 없으면 인증을 거부한다', () => {
            const guard = new AuthGuard(reflectorFor(false));

            expect(() => guard.canActivate(contextFor({}))).toThrow(UnauthorizedException);
        });

        it('역할 제한이 없으면 인증된 사용자를 허용한다', () => {
            const guard = new AuthGuard(reflectorFor(false));

            expect(guard.canActivate(contextFor())).toBe(true);
        });

        it('요구 역할과 사용자 역할이 같으면 접근을 허용한다', () => {
            const guard = new AuthGuard(reflectorFor(false, [UserRole.ADMIN]));

            expect(guard.canActivate(contextFor({ 'x-user-id': '42', 'x-user-role': UserRole.ADMIN }))).toBe(true);
        });

        it('요구 역할과 사용자 역할이 다르면 접근을 거부한다', () => {
            const guard = new AuthGuard(reflectorFor(false, [UserRole.ADMIN]));

            expect(() => guard.canActivate(contextFor())).toThrow(ForbiddenException);
        });
    });
});
