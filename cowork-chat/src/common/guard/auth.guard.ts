import {
	CanActivate,
	ExecutionContext,
	ForbiddenException,
	Injectable as injectable,
} from '@nestjs/common';
import {Reflector} from '@nestjs/core';
import {Request} from 'express';
import {AccountPolicyService, type ChatAccountPermission} from '../kafka/account-policy.service.js';
import {requestContextUtil} from '../util/request-context.util.js';
import {PUBLIC_METADATA_KEY} from './public.decorator.js';
import {ACCOUNT_PERMISSION} from './account-permission.decorator.js';

@injectable()
export class AuthGuard implements CanActivate {
	constructor(private readonly reflector: Reflector, private readonly policies: AccountPolicyService) {}

	async canActivate(context: ExecutionContext): Promise<boolean> {
		if (context.getType() !== 'http') {
			return true;
		}

		const isPublic = this.reflector.getAllAndOverride<boolean>(PUBLIC_METADATA_KEY, [
			context.getHandler(),
			context.getClass(),
		]);
		if (isPublic) {
			return true;
		}

		const request = context.switchToHttp().getRequest<Request>();
		const {path} = request;
		if (path === '/metrics') {
			return true;
		}

		const userId = requestContextUtil.getUserId(request.headers);

		const permission = this.reflector.getAllAndOverride<ChatAccountPermission | undefined>(ACCOUNT_PERMISSION, [
			context.getHandler(),
			context.getClass(),
		]);
		if (permission === undefined) {
			return true;
		}

		if (!await this.policies.allows(userId, permission)) {
			throw new ForbiddenException('접근 권한이 없습니다');
		}

		return true;
	}
}
