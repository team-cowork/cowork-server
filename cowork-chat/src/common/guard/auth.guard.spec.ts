import {type ExecutionContext, ForbiddenException, UnauthorizedException} from '@nestjs/common';
import {type Reflector} from '@nestjs/core';
import {type AccountPolicyService, type ChatAccountPermission} from '../kafka/account-policy.service.js';
import {ACCOUNT_PERMISSION} from './account-permission.decorator.js';
import {AuthGuard} from './auth.guard.js';

const allows = jest.fn();
const policies = {allows} as unknown as AccountPolicyService;

const defaultHeaders = (): Record<string, string> => ({'x-user-id': '42'});

const contextFor = (
	headers = defaultHeaders(),
	path = '/chat',
	type = 'http',
): ExecutionContext =>
	({
		getType: () => type,
		getHandler: jest.fn(),
		getClass: jest.fn(),
		switchToHttp: () => ({getRequest: () => ({headers, path})}),
	}) as unknown as ExecutionContext;

const reflectorFor = (isPublic: boolean, permission?: ChatAccountPermission): Reflector =>
	({
		getAllAndOverride: jest.fn((key: string) => key === ACCOUNT_PERMISSION ? permission : isPublic),
	}) as unknown as Reflector;

describe('AuthGuard', () => {
	describe('canActivate', () => {
		it('HTTP가 아닌 컨텍스트는 사용자 헤더 없이 통과시킨다', async () => {
			const guard = new AuthGuard(reflectorFor(false), policies);

			expect(await guard.canActivate(contextFor({}, '/chat', 'ws'))).toBe(true);
		});

		it('public endpoint와 metrics endpoint는 사용자 헤더 없이 통과시킨다', async () => {
			expect(await new AuthGuard(reflectorFor(true), policies).canActivate(contextFor({}))).toBe(true);
			expect(await new AuthGuard(reflectorFor(false), policies).canActivate(contextFor({}, '/metrics'))).toBe(true);
		});

		it('보호된 endpoint에 유효한 사용자 ID가 없으면 인증을 거부한다', async () => {
			const guard = new AuthGuard(reflectorFor(false), policies);

			await expect(guard.canActivate(contextFor({}))).rejects.toThrow(UnauthorizedException);
		});

		it('역할 제한이 없으면 인증된 사용자를 허용한다', async () => {
			const guard = new AuthGuard(reflectorFor(false), policies);

			expect(await guard.canActivate(contextFor())).toBe(true);
		});

		it('같은 요청자도 권한 회수 후 접근을 거부한다', async () => {
			const guard = new AuthGuard(reflectorFor(false, 'manage_chat_search_index'), policies);

			allows.mockResolvedValueOnce(true).mockResolvedValueOnce(false);
			const context = contextFor({'x-user-id': '42', 'x-user-role': 'ADMIN'});
			expect(await guard.canActivate(context)).toBe(true);
			await expect(guard.canActivate(context)).rejects.toThrow(ForbiddenException);
			expect(allows).toHaveBeenCalledWith(42, 'manage_chat_search_index');
		});

		it('계정 권한이 없으면 접근을 거부한다', async () => {
			const guard = new AuthGuard(reflectorFor(false, 'manage_chat_search_index'), policies);

			await expect(guard.canActivate(contextFor())).rejects.toThrow(ForbiddenException);
		});
	});
});
