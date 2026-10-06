import { createParamDecorator, type ExecutionContext } from '@nestjs/common';
import { type Request } from 'express';
import { RequestContextUtil } from '../util/request-context.util';

export const UserId = createParamDecorator(
    (data: unknown, ctx: ExecutionContext): number => {
        const request = ctx.switchToHttp().getRequest<Request>();
        return RequestContextUtil.getUserId(request.headers);
    },
);

export const UserRole = createParamDecorator(
    (data: unknown, ctx: ExecutionContext): string => {
        const request = ctx.switchToHttp().getRequest<Request>();
        return RequestContextUtil.getUserRole(request.headers);
    },
);
