import {
	CanActivate,
	ExecutionContext,
	ForbiddenException,
	Injectable as injectable,
} from '@nestjs/common';
import {Reflector} from '@nestjs/core';
import {Request} from 'express';
import {UserRole} from '../enum/user-role.enum.js';
import {requestContextUtil} from '../util/request-context.util.js';
import {PUBLIC_METADATA_KEY} from './public.decorator.js';
import {ROLES_KEY} from './roles.decorator.js';

@injectable()
export class AuthGuard implements CanActivate {
	constructor(private readonly reflector: Reflector) {}

	canActivate(context: ExecutionContext): boolean {
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

		requestContextUtil.getUserId(request.headers);

		const requiredRoles = this.reflector.getAllAndOverride<UserRole[] | undefined>(ROLES_KEY, [
			context.getHandler(),
			context.getClass(),
		]);
		if (!requiredRoles || requiredRoles.length === 0) {
			return true;
		}

		const userRole = request.headers['x-user-role'] as string | undefined;
		if (!requiredRoles.includes(userRole as UserRole)) {
			throw new ForbiddenException('접근 권한이 없습니다');
		}

		return true;
	}
}
