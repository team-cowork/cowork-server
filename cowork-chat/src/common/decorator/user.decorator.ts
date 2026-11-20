import {createParamDecorator, type ExecutionContext} from '@nestjs/common';
import {type Request} from 'express';
import {requestContextUtil} from '../util/request-context.util.js';

export const currentUserId = createParamDecorator(
	(data: unknown, ctx: ExecutionContext): number => {
		const request = ctx.switchToHttp().getRequest<Request>();
		return requestContextUtil.getUserId(request.headers);
	},
);
