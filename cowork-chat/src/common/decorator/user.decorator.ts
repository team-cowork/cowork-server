import {createParamDecorator, type ExecutionContext} from '@nestjs/common';
import {type Request} from 'express';
import {requestContextUtil} from '../util/request-context.util.js';

export const currentUserId = createParamDecorator(
	(data: unknown, ctx: ExecutionContext): number => {
		const request = ctx.switchToHttp().getRequest<Request>();
		return requestContextUtil.getUserId(request.headers);
	},
);

export const currentUserRole = createParamDecorator(
	(data: unknown, ctx: ExecutionContext): string => {
		const request = ctx.switchToHttp().getRequest<Request>();
		return requestContextUtil.getUserRole(request.headers);
	},
);

export type CurrentUser = {
	userId: number;
	userRole: string;
};

/**
`currentUserId()`와 `currentUserRole()`을 한 번에 읽는다. ID 헤더 검증이 역할보다 먼저 실패하는 순서도 같다.
*/
export const currentUser = createParamDecorator(
	(data: unknown, ctx: ExecutionContext): CurrentUser => {
		const request = ctx.switchToHttp().getRequest<Request>();
		return {
			userId: requestContextUtil.getUserId(request.headers),
			userRole: requestContextUtil.getUserRole(request.headers),
		};
	},
);
